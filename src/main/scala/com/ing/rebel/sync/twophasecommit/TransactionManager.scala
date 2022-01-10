package com.ing.rebel.sync.twophasecommit

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.fsm.PersistentFSM.{Normal, Reason}
import akka.persistence.fsm.{LoggingPersistentFSM, PersistentFSM}
import cats.data.NonEmptyChain
import com.ing.rebel.RebelError.{GenericRebelError, SyncFailed}
import com.ing.rebel.config.{LockMechanism, Parallel, RebelConfig, Sequential}
import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.messages.RebelMessage
import com.ing.rebel.specification.Specification
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.util.LoggingInterceptor
import com.ing.rebel.util.tracing.FsmTraceMetrics
import com.ing.rebel.{RebelConditionCheck, RebelErrors, RebelState, RebelSyncEvent}
import kamon.Kamon

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

object TransactionManager {
  sealed trait ManagerState extends RebelState
  case object Init extends ManagerState
  case object Wait extends ManagerState
  case object Abort extends ManagerState
  case object Commit extends ManagerState

  // participant is Seq to reduce problem with Kryo serialisation (which tries to serialise the ordering, which is a lambda and fails)
  case class ManagerData(requester: ActorRef, transactionId: TransactionId, participants: Seq[SyncOperation],
                         remainingAcks: Set[ParticipantId], failureReasons: RebelErrors
                        )

  sealed trait ManagerEvent extends SerializeWithKryo
  case class Initialized private(requester: ActorRef, transactionId: TransactionId, participants: Seq[SyncOperation]) extends ManagerEvent
  object Initialized {
    // make sure always sorted
    // no SortedSet, because of serialisation problems with Kryo and Lambda's
    def apply(requester: ActorRef, transactionId: TransactionId, participants: Set[SyncOperation]): Initialized =
      new Initialized(requester, transactionId, participants.toSeq.sorted)

    // override default factory constructor, since otherwise it will be still available
    private def apply(requester: ActorRef, transactionId: TransactionId, participants: Seq[SyncOperation]): Initialized =
      new Initialized(requester, transactionId, participants)
  }
  case class VoteCommitted(participant: ParticipantId, nestedParticipants: Set[SyncOperation]) extends ManagerEvent
  case class AbortReasonsAdded(reasons: RebelErrors) extends ManagerEvent
  case class GlobalCommitAcked(participant: ParticipantId) extends ManagerEvent
  case object GlobalCommitInitiated extends ManagerEvent

  case class Stop(reason: Reason = Normal) extends SerializeWithKryo

  val props: Props = Props(new TransactionManager())
}

class TransactionManager()(override implicit val domainEventClassTag: ClassTag[TransactionManager.ManagerEvent])
    extends PersistentFSM[TransactionManager.ManagerState, TransactionManager.ManagerData, TransactionManager.ManagerEvent]
    with LoggingPersistentFSM[TransactionManager.ManagerState, TransactionManager.ManagerData, TransactionManager.ManagerEvent]
    with ActAfterXTimeouts[TransactionManager.ManagerState, TransactionManager.ManagerData, TransactionManager.ManagerEvent]
    with LoggingInterceptor
//    with ActorVisualization
    with FsmTraceMetrics[TransactionManager.ManagerState, TransactionManager.ManagerData, TransactionManager.ManagerEvent] {

  import TransactionManager._
  import context.system

  // Useful for debugging unexpected messages
  //  whenUnhandled {
  //    case Event(e, d) if e.toString.contains("Failure") =>
  //      log.error("FAILURE {} {} ", e.toString, d.toString)
  //      stay()
  //  }

  override val persistenceId: String = s"2pc-manager-${self.path.name}"

  override val maximumTimeoutDuration: Duration = RebelConfig(context.system).rebelConfig.sync.twoPC.managerTimeout

  val lockMechanism: LockMechanism = RebelConfig(context.system).rebelConfig.sync.twoPC.lockMechanism

  //  override def recovery: Recovery = Recovery.none

  // Non-idiomatic, quick and dirty hack to handle uninitialised state
  startWith(Init, ManagerData(null, TransactionId(null), null, null, RebelErrors.of(GenericRebelError("Initial internal state, should not be visible"))))

  override def applyCommand(state: ManagerState, data: ManagerData): Unit = state match {
    case Init   => log.warning("TransactionManager created but not initialized within {}", nextTimeoutAt)
    case Wait   => voteRequest(data)
    case Commit => globalCommit(data)
    case Abort  => globalAbort(data)
  }

  override def stateAfterMaxRetries: PartialFunction[(ManagerState, ManagerData), State] = {
    case (Wait, _) =>
      goto(Abort) applying
        AbortReasonsAdded(RebelErrors.of(SyncFailed(s"TransactionManager waiting but not received all votes within $nextTimeoutAt"))) andThen
        globalAbort
  }

  // 1. The coordinator sends a VOTE_REQUEST message to all participants.
  def voteRequest(data: ManagerData): Unit = {
    sendVoteRequest(data.participants)
  }

  def sendVoteRequest(participants: Seq[SyncOperation]): Unit = {
    val remainingParticipants: Seq[SyncAction[_ <: Specification]] = lockMechanism match {
      case Parallel => participants.filter(action => stateData.remainingAcks.contains(action.participantId))
      // Only take first when sequentially locking
      case Sequential => participants.find(action => stateData.remainingAcks.contains(action.participantId)).toList
    }

    remainingParticipants.foreach(p => {
      val event: RebelSyncEvent[_ <: Specification] = p.syncEvent
      val request: VoteRequest = VoteRequest(stateData.transactionId, p.participantId, event)
      log.debug("Sending {} to participant: {}", request, p.participantId)
      p.contactPoint.tell(request)
    })
  }

  // If all participants have voted to commit the transaction, then so will the coordinator.
  // In that case, it sends a GLOBAL_COMMIT message to all participants.
  def globalCommit(data: ManagerData): Unit = {
    val remainingParticipants = data.participants.filter(p => data.remainingAcks.contains(p.participantId))
    deliverMsgToParticipants(GlobalCommit(data.transactionId), remainingParticipants)
    val requester = data.requester
    val result = TransactionResult(data.transactionId, RebelConditionCheck.success)
    log.debug("{} to requester: {}", result, requester)
    requester ! result
  }

  def globalAbort(data: ManagerData): Unit = {
    deliverMsgToParticipants(GlobalAbort(data.transactionId), data.participants)
    val requester = data.requester
    log.debug("TransactionAborted to requester: {}", requester)
    requester ! TransactionResult(data.transactionId, RebelConditionCheck.failure(data.failureReasons))
  }

  when(Init, retryDuration) {
    case Event(Initialize(transactionId, participants), data) if participants.isEmpty =>
      goto(Commit)
        .applying(Initialized(sender(), transactionId, participants), GlobalCommitInitiated)
        .andThen(globalCommit)
    case Event(Initialize(transactionId, participants), data)                         =>
      goto(Wait) applying Initialized(sender(), transactionId, participants) andThen voteRequest
  }
  /**
    * 3. The coordinator collects all votes from the participants.
    * If all partic-ipants have voted to commit the transaction, then so will the coor- dinator.
    * In that case, it sends a GLOBAL_COMMIT message to all participants.
    * However, if one participant had voted to abort the trans- action,
    * the coordinator will also decide to abort the transaction and multicasts a GLOBAL_ABORT message.
    */
  when(Wait, retryDuration) {
    // TODO maybe refactor to DurableDM, send wrapped, then use receive pipeline to send ack back.
    case Event(e@VoteCommit(id, participantId, nestedParticipants), data)         =>
      log.debug("{}({}) voted commit.", participantId, sender)
      val voteCommitted = VoteCommitted(participantId, nestedParticipants)
      val newRemainingAcks = data.remainingAcks ++ nestedParticipants.map(_.participantId)
      // compare size against newRemainingAcks to know if done, previous remaining acks should contain this
      if (newRemainingAcks.size == 1 && data.remainingAcks.contains(participantId)) {
        log.debug("All participants voted, committing!")
        assert(nestedParticipants.isEmpty, "There should be no new nested participants.")
        goto(Commit) applying(voteCommitted, GlobalCommitInitiated) andThen globalCommit
      } else {
        stay() applying voteCommitted andThen { newData =>
          lockMechanism match {
              // send to nested participants
            case Parallel => sendVoteRequest(nestedParticipants.toSeq.sorted)
            // send to next participant
            case Sequential =>
              val remainingParticipants = newData.participants.filter(a => newData.remainingAcks.contains(a.participantId))
              sendVoteRequest(remainingParticipants)
          }
          log.debug("Remaining votes {}", newData.remainingAcks)
        }
      }
    case Event(e@VoteAbort(id, participantId, reasons), data) =>
      goto(Abort) applying AbortReasonsAdded(reasons) andThen globalAbort
  }

  when(Commit, retryDuration) {
    // TODO collect all before committing
    case Event(e@GlobalCommitAck(id, participantId), data) =>
      // DONE, stop until rewoken (rewaked?, rewaken?)
      val acked = GlobalCommitAcked(participantId)
      if (data.remainingAcks.size == 1 && data.remainingAcks.contains(participantId)) {
        log.debug("All participants committed, stopping!")
        stay() applying acked andThen (_ => stopNow())
      } else {
        stay() applying acked andThen (newData => log.debug("Remaining GlobalCommitAcks {}", newData.remainingAcks))
      }
    // for any command, resend last response, to help out of date participants to get up to speed,
    // TODO refactor to whenUnhandled, but that one overwrites unhandled from trait
    case Event(c: Command, data) =>
      globalCommit(data)
      stopNow()
  }
  when(Abort, retryDuration) {
    // TODO collect all before aborting
    // see GlobalCommitAck for pattern
    case Event(e@GlobalAbortAck(id, participantId), data) =>
      // DONE, stop until rewoken (rewaked?, rewaken?)
      stopNow()
    // for any command, resend last response, to help out of date participants to get up to speed
    case Event(c: Command, data) =>
      globalAbort(data)
      stopNow()
  }
  // TODO handle cyclic nested sync graphs
  override def applyEvent(domainEvent: ManagerEvent, currentData: ManagerData): ManagerData = {
    log.debug("applyEvent: {} {}", domainEvent, currentData)
    domainEvent match {
      case Initialized(requester, transactionId, participants) =>
        // failed reasons is default sync failed message to prevent null. It should work out, since it only sent back to the requester when really failed.
        ManagerData(requester, transactionId, participants, participants.map(_.participantId).toSet, RebelErrors.of(SyncFailed(s"Sync `$transactionId` failed")))
      case VoteCommitted(participantId, nestedParticipants)                        =>
        log.debug("Removing {}, adding nested participants {}", participantId, nestedParticipants)
        val newRemainingAcks = currentData.remainingAcks ++ nestedParticipants.map(_.participantId)
        if (!newRemainingAcks.contains(participantId)) {
          if (currentData.participants.exists(_.participantId == participantId)) {
            // can happen when re-asked VoteCommit
            log.info("Removing {}, not in {} (can happen when re-asked VoteCommit)", participantId, newRemainingAcks)
          } else {
            log.error("Removing and unknown participant {}, but not in {}", participantId, currentData.remainingAcks)
          }
        }
        currentData.copy(remainingAcks = newRemainingAcks - participantId, participants = currentData.participants ++ nestedParticipants)
      case GlobalCommitInitiated                               =>
        currentData.copy(remainingAcks = currentData.participants.map(_.participantId).toSet) // reset for counting Acks
      case GlobalCommitAcked(participantId)                    =>
        log.debug("Removing {}", participantId)
        if (!currentData.remainingAcks.contains(participantId)) {
          if (currentData.participants.exists(_.participantId == participantId)) {
            log.warning("Removing {}, but not in {}", participantId, currentData.remainingAcks)
          } else {
            // Really weird, because should be blocked by EH
            log.error("Removing and unknown participant {}, but not in {}", participantId, currentData.remainingAcks)
          }
        }
        currentData.copy(remainingAcks = currentData.remainingAcks - participantId)
      case AbortReasonsAdded(reasons)                          =>
        currentData.copy(failureReasons = currentData.failureReasons.concat(reasons))
    }
  }

  def incrementKamonCounter(counterName: String): Unit = {
    if(!recoveryRunning) {
      Kamon.counter(counterName).withoutTags.increment()
    }
  }

  onTransition{
    case _ -> Commit => incrementKamonCounter("TransactionManager.Commit")
    case _ -> Abort => incrementKamonCounter("TransactionManager.Abort")
  }

  def deliverMsgToParticipants(msg: RebelMessage, participants: Seq[SyncOperation]): Unit = {
    log.debug("Sending {} to participants: {}", msg, participants)
    participants.foreach(sa => sa.contactPoint.tell(msg))
  }

  override def stopNow(reason: PersistentFSM.Reason = Normal): State = {
    log.info("Initiating Passivation")
    context.parent ! Passivate(Stop(reason))
    // let passivation handle the shutdown
    stay()
  }

  Set(Init, Wait, Abort, Commit).foreach(when(_) {
    case Event(Stop(reason), _) => stop(reason)
  })

  onTermination {
    case StopEvent(reason, _, _) =>
      // No passivate needed because run in sharding
      // TODO maar waarom dit dan?
      // [info] [DEBUG] [04/13/2019 10:29:04.683] [IsolatedCluster_testttMum-akka.actor.default-dispatcher-18] [akka://IsolatedCluster_testttMum@localhost:59548/system/sharding/2pc/89]
//       Entity stopped after passivation [t-4-4-0]
      // toch maar wel
//      context.parent ! Passivate(Shutdown)
      log.debug("Stopping because of {}", reason)
  }

  override def receiveRecover: Receive = {
    case x =>
      if (recoveryRunning) {
        log.debug("recovering: {}", x)
      }
      super.receiveRecover(x)
  }

  override lazy val retryDuration: FiniteDuration = RebelConfig(context.system).rebelConfig.sync.twoPC.managerRetryDuration
}