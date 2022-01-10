package com.ing.rebel.sync.twophasecommit

import akka.actor.Props
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.Failure
import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import com.ing.rebel.RebelError.{SyncFailed, WrappedError}
import com.ing.rebel.config.RebelConfig
import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.messages._
import com.ing.rebel.specification.Specification
import com.ing.rebel.sync.twophasecommit.TransactionParticipant._
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.util.LoggingInterceptor
import com.ing.rebel.util.MessageLogger.CommentEvent
import com.ing.rebel.util.tracing.FsmTraceMetrics
import com.ing.rebel.{IsInitialized, QuerySyncEvent, RebelDomainEvent, RebelErrors, RebelState, RebelSyncEvent}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag

object TransactionParticipant {
  sealed trait ParticipantState extends RebelState
  case object Init extends ParticipantState
  case object Checking extends ParticipantState
  case object Ready extends ParticipantState
  case object Abort extends ParticipantState
  case object Commit extends ParticipantState

  case class ParticipantData(transactionId: TransactionId, request: RebelSyncEvent[_ <: Specification],
                             participantId: ParticipantId, failureReasons: RebelErrors,
                             nestedParticipants: Set[SyncOperation])

  sealed trait ParticipateEvent extends SerializeWithKryo
  case class Initialized(
                          transactionId: TransactionId, request: RebelSyncEvent[_ <: Specification], participantId: ParticipantId
                        ) extends ParticipateEvent
  case class NestedParticipantsAdded(nestedParticipants: Set[SyncOperation]) extends ParticipateEvent
  case class AbortReasonsAdded(reasons: RebelErrors) extends ParticipateEvent

  //  def props(conditionCheck: Transaction => Boolean)(implicit domainEventClassTag: ClassTag[TransactionParticipant.ParticipateEvent]): Props =
  //    Props(new TransactionParticipant() {
  //      override def checkConditions(request: Transaction): Boolean = conditionCheck(request)
  //    })

  def props(entityContactPoint: ContactPoint, coordinatorContactPoint: ContactPoint): Props =
    Props(new TransactionParticipant(entityContactPoint, coordinatorContactPoint))

}

class TransactionParticipant[S <: Specification] private(entityContactPoint: ContactPoint, coordinator: ContactPoint)
                                    (override implicit val domainEventClassTag: ClassTag[ParticipateEvent])
  extends PersistentFSM[ParticipantState, ParticipantData, ParticipateEvent]
    with ActAfterXTimeouts[ParticipantState, ParticipantData, ParticipateEvent]
    with LoggingInterceptor
    with FsmTraceMetrics[ParticipantState, ParticipantData, ParticipateEvent] {

  import TransactionParticipant._

  override val persistenceId: String = s"2pc-participant-${self.path.parent.name}-${self.path.name}"

  override val maximumTimeoutDuration: Duration = RebelConfig(context.system).rebelConfig.sync.twoPC.participantTimeout

  //  override def recovery: Recovery = Recovery.none

  log.debug("Startup TransactionParticipant for {}", entityContactPoint)

  override def applyCommand(state: ParticipantState, data: ParticipantData): Unit = state match {
    case Init     =>
      if (timeoutCounter > 3) {
        log.warning("TransactionParticipant created but not asked to vote within {}", nextTimeoutAt)
      } else {
        log.info("TransactionParticipant created but not asked to vote within {}", nextTimeoutAt)
      }
    case Checking => checkConditions(data)
    case Ready    => voteCommit(data)
    case Commit   => globalCommitAck(data)
    case Abort    => globalAbortAck(data)
  }

  override def stateAfterMaxRetries: PartialFunction[(ParticipantState, ParticipantData), State] = {
    case (Init, _)     => goto(Abort) applying
      AbortReasonsAdded(RebelErrors.of(SyncFailed(s"TransactionParticipant created but not asked to vote within $nextTimeoutAt")))
    case (Checking, _) => goto(Abort) applying
      AbortReasonsAdded(RebelErrors.of(SyncFailed(s"TransactionParticipant asked to vote, but no response from entity within $nextTimeoutAt")))
    case (Commit, _)   => stop(Failure(s"Time-out in state Commit"))
    case (Abort, _)    => stop(Failure(s"Time-out in state Abort"))
  }

  import context.system

  def checkConditions(data: ParticipantData): Unit = {
    val checkPreconditions: CheckPreconditions[_] = CheckPreconditions(data.request)
    //    publishMessageEvent(MessageEvent(self, entityContactPoint, checkPreconditions.toString))
    entityContactPoint.tell(checkPreconditions)
    //    context.parent ! checkPreconditions
    log.debug("Sending {} to {}", checkPreconditions, entityContactPoint)
  }

  /*
  2. When a participant receives a VOTE_REQUEST message, it returns either a VOTE_COMMIT message to the coordinator telling the coor-
     dinator that it is prepared to locally commit its part of the transaction,
     or otherwise a VOTE_ABORT message.
   */
  def voteCommit(data: ParticipantData): Unit = {
    val commit = VoteCommit(data.transactionId, data.participantId, data.nestedParticipants)
    log.debug("Sending {} to coordinator {}", commit, coordinator)
    coordinator.tell(commit)
  }

  def voteAbort(data: ParticipantData): Unit = {
    // TODO in principle the entity should become available directly again
    val abort = VoteAbort(data.transactionId, data.participantId, data.failureReasons)
    log.debug("Sending {} to coordinator {}", abort, coordinator)
    coordinator.tell(abort)
  }

  def releaseAndCommitOnEntity(data: ParticipantData, process: Boolean): Unit = {
    // TODO make fancy smansy with retries and timeout
    // or not, since for now it is assumed that this is local message passing only
    data.request match {
      case rd: S#RDomainEvent =>
        log.debug("Committing {} on entity {}", rd, entityContactPoint)
        entityContactPoint.tell(ReleaseEntity[S](rd, process = process))
      case qse: QuerySyncEvent[S]       =>
        log.debug("Releasing {} on entity {}", qse, entityContactPoint)
      // do nothing, because no side effects of queries
        entityContactPoint.tell(ReleaseEntity[S](qse, false))
    }
  }

  def globalCommitAck(data: ParticipantData): Unit = {
    val commitAck = GlobalCommitAck(data.transactionId, data.participantId)
    log.debug("Sending {} to coordinator {}", commitAck, coordinator)
    coordinator.tell(commitAck)
  }

  def globalAbortAck(data: ParticipantData): Unit = {
    val abortAck = GlobalAbortAck(data.transactionId, data.participantId)
    log.debug("Sending {} to coordinator {}", abortAck, coordinator)
    coordinator.tell(abortAck)
  }

  startWith(Init, ParticipantData(TransactionId(null), null, ParticipantId(TransactionId("unset"), null), RebelErrors.of(SyncFailed(s"Uninitialized")), Set()))
  when(Init, retryDuration) {
    case Event(VoteRequest(id, participantId, request), _) =>
      goto(Checking) applying Initialized(id, request, participantId) andThen checkConditions
      // Can happen when other participants votes `VoteAbort`.
    case Event(GlobalAbort(id), _) => goto(Abort) andThen { data =>
      globalCommitAck(data)
      // use dummy event, because EntityHost requires it...
      val dummyEvent = IsInitialized(id)
      entityContactPoint.tell(ReleaseEntity[S](dummyEvent, process = false))
      // can't release without data yet
      context.stop(self)
    }
  }

  // TODO also respond when transaction id doesnt match
  when(Checking, retryDuration) {
    case Event(CheckPreconditionsResult(tId, Valid(syncParticipants)), _) if tId == stateData.transactionId         =>
      goto(Ready) applying NestedParticipantsAdded(syncParticipants) andThen voteCommit
    case Event(CheckPreconditionsResult(tId, Invalid(reasons)), _) if tId == stateData.transactionId =>
      import cats.implicits._
      val wrappedReasons = reasons.map(WrappedError(entityContactPoint.toString, _))
      goto(Abort) applying AbortReasonsAdded(wrappedReasons) andThen { data =>
        releaseAndCommitOnEntity(data, process = false)
        voteAbort(data)
      }
    case Event(m@CheckPreconditionsResult(tId, _),_) =>
      log.error("Received {} for wrong tId {} instead of {}", m, tId, stateData.transactionId)
      stay()
  }

  /**
    * 4. Each participant that voted for a commit waits for the final reaction by the coordinator.
    * If a participant receives a GLOBAL_COMMIT message, it locally commits the transaction.
    * Otherwise, when receiving a GLOBAL_ABORT message, the transaction is locally aborted as well.
    */
  when(Ready, retryDuration) {
    case Event(e@GlobalCommit(id), data)             =>
      log.debug("{} -> Committing!", e)
      goto(Commit) andThen (data => {
        globalCommitAck(data)
        releaseAndCommitOnEntity(data, process = true)
        context.stop(self) // stop(), does not work here, only as last statement of of state handler
      })
    case Event(e@GlobalAbort(id), data)              =>
      log.debug("{} -> Aborting!", e)
      goto(Abort) andThen (data => {
        globalAbortAck(data)
        releaseAndCommitOnEntity(data, process = false)
        context.stop(self) // stop(), does not work here, only as last statement of of state handler
      })
    case Event(CheckPreconditionsResult(_, _), data) =>
      // ignore event, since it is a retry from a previous state
      stay()
  }

  when(Commit) {
    case Event(StateTimeout, data)                                             =>
      // after timeout
      stop()
    case Event(GlobalCommit(id), data)                                         =>
      // redeliver Ack, but don't persist anything
      globalCommitAck(data)
      stop()
    case Event(EntityCommandSuccess(command), data) if data.request == command =>
      // happens after commit to entity
      // do nothing, be glad it is a success. TODO stop retrying Commit maybe?
      stop()
      // Happens if stash is full in EntityHost. For now retry.
    case Event(EntityTooBusy, data) =>
      stay() andThen (data => releaseAndCommitOnEntity(data, process = true))
    // for any command, resend last response, to help out of date participants to get up to speed
    case Event(c: Command, data) =>
      globalCommitAck(data)
      stop()
  }
  when(Abort) {
    case Event(StateTimeout, data)      =>
      // after timeout
      stop()
    case Event(e@GlobalAbort(id), data) =>
      // redeliver Ack, but don't persist anything
      globalAbortAck(data)
      stop()
    // Happens if stash is full in EntityHost. For now retry. Also here to make sure entity is released
    case Event(EntityTooBusy, data) =>
      stay() andThen (data => releaseAndCommitOnEntity(data, process = false))
    case Event(c: Command, data)        =>
      log.debug("Received command {} from {}, but in Abort state, replying GlobalAbortAck", c, sender())
      globalAbortAck(data)
      stop()
  }

  // when receiving unknown message respond with latest response of current 2PC state
  Set(Init, Ready, Commit, Abort, Checking) foreach {
    state =>
      when(state) {
        case Event(c: Command, data) =>
          log.debug("Received unexpected command `{}`", c.toString)
          //          applyCommand(state, data)
          stay()
      }
  }


  override def applyEvent(domainEvent: ParticipateEvent, currentData: ParticipantData): ParticipantData = {
    log.debug("applyEvent: {} {}", domainEvent, currentData)
    domainEvent match {
      case Initialized(id, request, participantId)     =>
        ParticipantData(id, request, participantId,
          RebelErrors.of(SyncFailed(s"Sync `$request` for `$participantId` failed")), Set())
      case NestedParticipantsAdded(nestedParticipants) =>
        currentData.copy(nestedParticipants = currentData.nestedParticipants ++ nestedParticipants)
      case AbortReasonsAdded(reasons)                  =>
        currentData.copy(failureReasons = currentData.failureReasons.concat(reasons))
    }
  }

  // TODO refactor or use FSMActorLogging
  if(log.isDebugEnabled || context.system.settings.config.getBoolean("rebel.visualisation.enabled")) {
    onTransition({
      case (from, to) =>
        log.debug("Moving from state {} to state {}", from, to)
        publishMessageEvent(CommentEvent(self, to.toString))
    })
  }

  onTermination {
    case StopEvent(reason, _, _) =>
      publishMessageEvent(CommentEvent(self, s"Stop: $reason"))
      log.debug("Stopping because of {}", reason)
  }
  override lazy val retryDuration: FiniteDuration = RebelConfig(context.system).rebelConfig.sync.twoPC.participantRetryDuration
}
