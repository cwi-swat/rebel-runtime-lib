package com.ing.rebel.sync.twophasecommit

import akka.actor.SupervisorStrategy.{Escalate, Resume}
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Stash, StashOverflowException, Terminated}
import akka.persistence.{ReplyToStrategy, RuntimePluginConfig, StashOverflowStrategy}
import akka.util.Timeout
import cats.data.NonEmptyChain
import com.ing.rebel.RebelError.CommandNotAllowedInState
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.config.RebelConfig
import com.ing.rebel.messages.{RebelCommand, _}
import com.ing.rebel.specification.{RebelSpecification, Specification, SpecificationInfo}
import com.ing.rebel.sync.TwoPhaseCommitManagerSharding
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Reject.{DynamicRejectReason, StaticRejectReason}
import com.ing.rebel.sync.pathsensitive.{AllowCommandDecider, StaticCommandDecider, TwoPLCommandDecider}
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider._
import com.ing.rebel.sync.twophasecommit.EntityHost.{CircularBuffer, _}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.util.MessageLogger.{CommentEvent, MessageEvent}
import com.ing.rebel.util.{LoggingInterceptor, PassivationForwarder}
import com.ing.rebel.{QuerySyncEvent, RebelCheck, RebelConditionCheck, RebelDomainEvent, RebelError, RebelErrors, RebelState, RebelSuccess, RebelSyncEvent, SpecificationEvent}
import kamon.Kamon

import scala.collection.{immutable, mutable}
import scala.collection.immutable.ListMap
import scala.reflect.ClassTag
import scala.collection.immutable.Seq

object EntityHost {
  /**
    * FIFO buffer. Oldest items fall out. Used to limit the transaction history and to not let it grow infinite.
    *
    * @tparam T item type in the buffer
    */
  type CircularBuffer[T] = immutable.Vector[T]

  object CircularBuffer {

    import scala.collection.immutable

    def empty[T]: CircularBuffer[T] = immutable.Vector.empty[T]

    def addToCircularBuffer[T](maxSize: Int)(buffer: CircularBuffer[T], item: T): CircularBuffer[T] =
      (buffer :+ item) takeRight maxSize
  }

  def participantActorName(id: TransactionId): String = s"2pc-p-$id"

  val transactionHistorySize = 100
  //  val maxTransactionsInProgress = 4
}

trait EntityHost[S <: Specification] extends
  //    Actor with
  Stash with PassivationForwarder with LoggingInterceptor {
  specification: RebelFSMActor[S] with RebelSpecification[S] =>

  // bit hacky
  val entityTypeName: String = getClass.getSimpleName

  /**
    * Hosts the entity. Has 2 child actors type:
    * 1. The Specification actor with Logic and Data
    * 2. 1 (or more) Transaction Participants that handle transactions
    * #. the active Transaction Manager (now sharded under 2pc)
    */
  log.debug("Startup EntityHost containing {}", entityTypeName)
  Kamon.counter(s"create-$entityTypeName").withoutTags().increment()
  log.debug("Entity started on {}", self.path)

  type ParticipantRef = ActorRef
  //  var activeTransactionId: Option[TransactionId] = None
  type Requester = ActorRef

  // TODO make lazy?
  val rebelConfig: RebelConfig = RebelConfig(context.system).rebelConfig
  //  val maxTransactionsInProgress = rebelConfig.sync.maxTransactionsInProgress

  val commandDecider: AllowCommandDecider[S] = allowCommandDecider(rebelConfig, specification)

  // TODO make this persistent?
  /**
    * Tracks active transaction participants, should be removed when state is updated.
    * Some for events, None for Queries. Option is some when ready for release.
    */
  case class TransactionInfo(domainEvent: Option[RDomainEvent], releaseEntityMessage: Option[ReleaseEntity[S]])
  val activeTransactions: mutable.LinkedHashMap[TransactionId, TransactionInfo] = mutable.LinkedHashMap.empty
  /**
    * Tracks active transaction (manager)s that are waiting on response
    */
  var activeManagers: Map[TransactionId, (Requester, RDomainEvent)] = Map.empty
  var finishedTransactions: CircularBuffer[TransactionId] = CircularBuffer.empty

  def allowEvent(event: RDomainEvent): AllowCommandDecision = {
    log.debug("CommandDecider: Received event {} from {}", event, sender())
    val sampler = Kamon.spanBuilder("CommandDecider").start()
    //    if (activeTransactions.size >= maxTransactionsInProgress) {
    //      log.debug("Verdict by CommandDecider: Too many active transactions ({}), denying event {}", activeTransactions.size, event)
    //      false
    //    } else {
    // filter out queries
    val relevantTransactions: Seq[RDomainEvent] = activeTransactions.values.map(_.domainEvent).toList.flatten
    //    val startTime = System.nanoTime()
    val allowed = commandDecider.allowCommand(currentState, currentData, relevantTransactions, event)
    sampler.finish()
    //    log.debug("Command decider took {} nanos", System.nanoTime() - startTime)
    log.debug("Verdict by CommandDecider:  {} {}", event, allowed)
    publishMessageEvent(CommentEvent(self, s"CommandDecider:  $event $allowed"))

    allowed
  }

  val managerRelatedReceive: Receive = {
    case result: TwoPhaseCommit.TransactionResult
      // only if still active and requester not yet informed
      if activeManagers.contains(result.transactionId)                                                    =>
      // for simple sync request, no action needed
      // for others, tell about result to requester
      activeManagers.get(result.transactionId).foreach {
        case (requester, event) =>
          result.result.fold(
            { errors =>
              log.warning("Command {} failed because of: {}", event, errors)
              requester ! EntityCommandFailed(event, errors)
            },
            (_: RebelSuccess) => requester ! EntityCommandSuccess(event)
          )
          activeManagers -= result.transactionId
      }
    case result: TwoPhaseCommit.TransactionResult if !finishedTransactions.contains(result.transactionId) =>
      log.error("Received {}, with unknown/outdated transactionId", result)
    case result: TwoPhaseCommit.TransactionResult                                                         =>
      // ignore, because should be in finishedTransactions
      // requester should be already informed
      // TODO resend result...
      assert(finishedTransactions.contains(result.transactionId))
    // RebelCommand comes in, no active transaction, create manager + participant (effectively locking this entity)
    // Initiate manager
    case command: RebelCommand[S] =>
      val dEvent = command.domainEvent
      allowEvent(dEvent) match {
        case _: Accept                          =>
          //          log.debug("Creating Initialize message")
          //          log.debug("create2PCSelfSyncCommand: {}", create2PCSelfSyncCommand(dEvent))
          val initialize: RebelCheck[Initialize] = create2PCInitialize(dEvent)(specification.currentData)
          //          log.debug("Created Initialize message: {}", initialize)
          import cats.implicits._
          (initialize, syncOperationsGeneric(currentData, dEvent)).tupled.fold(
            { errors =>
              log.error("Command {} failed because of: {}", dEvent, errors)
              sender() ! EntityCommandFailed[S](dEvent, errors)
            },
            { case (init, sos) =>
              if (sos.isEmpty) {
                // no 2PC needed if no other participants
                // ready to release (but, possibly delayed because of arrival order)
                val releaseEntity = ReleaseEntity[S](dEvent, process = true)
                activeTransactions += (dEvent.transactionId -> TransactionInfo(Some(dEvent), Some(releaseEntity)))
                // trigger apply/release
                this.receive(releaseEntity)
                // trigger result
                activeManagers += dEvent.transactionId -> (sender(), dEvent)
                // can only be reached when success (checked in allowEvent)
                this.receive(TwoPhaseCommit.TransactionResult(dEvent.transactionId, RebelConditionCheck.success))
              } else {
                // start 2PC participant
                assert(init.participants.nonEmpty, s"Initialize $initialize should have at least self as participant.")
                log.debug("Creating participant for dEvent `{}` and entity `{}`", dEvent, self)
                getOrCreateParticipant(dEvent.transactionId)
                activeTransactions += (dEvent.transactionId -> TransactionInfo(Some(dEvent), None))
                publishMessageEvent(CommentEvent(self, s"added ${dEvent.transactionId} to $activeTransactions"))
                log.debug("Creating new TransactionManager for {}", dEvent)
                TwoPhaseCommitManagerSharding(context.system).tellManager(dEvent.transactionId, init)
                activeManagers += dEvent.transactionId -> (sender(), dEvent)
              }
            }
          )

        case Reject(reasons) =>
          val errors: RebelErrors = reasons.map {
            case StaticRejectReason     => RebelErrors.of(CommandNotAllowedInState(currentState, dEvent.specEvent))
            case d: DynamicRejectReason => d.errors
          }.reduce
          sender() ! EntityCommandFailed(dEvent, errors)
        case d: Delay        =>
          log.debug("{} not allowed by AllowCommandDecider, delaying: {}", command, d)
          unavailable(command)
      }
  }

  private def knownTransaction(tId: TransactionId): Boolean = activeTransactions.contains(tId) || finishedTransactions.contains(tId)

  val participantRelatedReceive: Receive = {
    // forward tpc messages to active participants
    case tpc: Command if knownTransaction(tpc.transactionId) =>
      val participant: ParticipantRef = getOrCreateParticipant(tpc.transactionId)
      log.debug("Forwarding {} to participant {}", tpc, participant)
      participant forward tpc

    // This is used for new transactions where this entity is not initiating.
    case voteRequest@VoteRequest(tid, pid, event: RDomainEvent) =>
      allowEvent(event) match {
        case _: Accept =>
          val participant: ParticipantRef = getOrCreateParticipant(tid)
          activeTransactions += (tid -> TransactionInfo(Some(event), None))
          participant forward voteRequest
        //        case Reject(reasons) if reasons.contains(StaticRejectReason)         =>
        //          sender() ! VoteAbort(tid, pid, RebelErrors.of(CommandNotAllowedInState(currentState, event.specEvent)))
        case Reject(reasons) =>
          val errors: RebelErrors = reasons.map {
            case StaticRejectReason     => RebelErrors.of(CommandNotAllowedInState(currentState, event.specEvent))
            case d: DynamicRejectReason => d.errors
          }.reduce
          sender() ! VoteAbort(tid, pid, errors)
        case d: Delay        =>
          log.info("Delay (reason: {}) {}", d, voteRequest)
          unavailable(voteRequest)
      }

    // Happens for query syncs, such as IsInitialized
    // TODO implement allowEvent for SyncQueries. These should also hold in all possible worlds
    case voteRequest@VoteRequest(tid, pid, event: QuerySyncEvent[S]) =>
      val participant: ParticipantRef = getOrCreateParticipant(tid)
      activeTransactions += (tid -> TransactionInfo(None, None))
      participant forward voteRequest

    case tpc: Command if !activeTransactions.contains(tpc.transactionId) =>
      // happens when actor is locked by other transaction
      //      sender() ! LockedInquiryResponse(activeTransactionId)
      // TODO don't use stash, since it looses messages when crashing
      // TODO make sure this only happens with new transaction inits... others could be dropped
      log.info("Receive 2pc command {} for unknown/new transaction", tpc)
      unavailable(tpc)

    // always forward Sync queries (e.g. CheckPreconditions and GetSyncInitialize) for correct 2PC
    case syncQuery: CheckPreconditions[S] =>
      log.debug("Forwarding msg to entity: {} to {}", syncQuery, self)
      super.receive(syncQuery)
    // always allow queries when no before indicator, else if id is not currently running
    case query: RebelQuery if query.after.fold(true) { afterId =>
      //      finishedTransactions.contains(afterId) ||
      !activeTransactions.contains(afterId)
    } =>
      log.debug("Handling query on entity: {} to {}", query, self)
      super.receive(query)
    // if in transaction => delay the response
    case query: RebelQuery                                                                           =>
      log.debug("Unknown query after id, not found in {}", activeTransactions.keys)
      // if msg comes in try to wait, or else bounce
      unavailable(query)
    case pe: ReleaseEntity[S] if activeTransactions.keys.headOption.contains(pe.event.transactionId) =>
      log.info("Marking for processing {}", pe)

      // set incoming Release to ready
      activeTransactions.get(pe.event.transactionId).fold(
        throw new Exception(s"${pe.event.transactionId} should always be in $activeTransactions")
      )(transactionInfo =>
        activeTransactions += (pe.event.transactionId -> transactionInfo.copy(releaseEntityMessage = Some(pe)))
      )

      // Start processing events until we reach a non-ready one
      val ready = activeTransactions.takeWhile(_._2.releaseEntityMessage.isDefined)
      log.info("Starting processing for {}", ready.keys)
      val toProcessEvents: Seq[ProcessEvent[S]] = ready.collect {
        case (id, TransactionInfo(Some(event), Some(release))) if release.process =>
          log.debug("Processing {} on entity", event)
          ProcessEvent(event): ProcessEvent[S]
      }.toList
      // process ready events
      processEvents(toProcessEvents)
      // release all that are ready
      ready.foreach(read => releaseTransaction(read._1))

      // If any messages where stashed, we can retry them now because commands have been processed
      if (ready.nonEmpty) {
        // TODO handle outdated messages, maybe wrapping them in stash with timeout, and handling it transparently, do time check when unstashing
        unstashAll()
        log.debug("Unstashing all")
        publishMessageEvent(CommentEvent(self, "Unstashing all"))
      }
    case pe: ReleaseEntity[S]                                                                        =>
      log.debug("Delaying {}, because other transactions should go first", pe)
      publishMessageEvent(CommentEvent(self, s"Delaying $pe, because other transactions should go first $activeTransactions"))
      //TODO Should somehow be persisted, so not lost on failure.
      //very bad if this fails, because effect is never applied
      log.debug("Had to delay {}, because of {}", pe, activeTransactions)
      activeTransactions.get(pe.event.transactionId).fold(
        throw new Exception(s"${pe.event.transactionId} should always be in $activeTransactions")
      )(transactionInfo =>
        activeTransactions += (pe.event.transactionId -> transactionInfo.copy(releaseEntityMessage = Some(pe)))
      )
  }

  def releaseTransaction(tId: TransactionId): Unit = {
    activeTransactions -= tId
    log.debug("Releasing {} from {} ", tId, activeTransactions.keys.toVector)
    publishMessageEvent(CommentEvent(self, s"releasing $tId from ${activeTransactions.keys.toVector}"))
    finishedTransactions = CircularBuffer.addToCircularBuffer(EntityHost.transactionHistorySize)(finishedTransactions, tId)
  }

  abstract override def receive: Receive = managerRelatedReceive.orElse(participantRelatedReceive).orElse(super.receive)

  lazy val lockClash = Kamon.counter("lockClash").withoutTags()

  private def unavailable(msg: RebelMessage): RebelSuccess = {
    log.debug("locked by {}, stashing {}", activeTransactions.keys, msg)
    lockClash.increment()
    publishMessageEvent(CommentEvent(self, s"locked by $activeTransactions"))
    publishMessageEvent(CommentEvent(self, s"Stashing $msg"))
    try stash() catch {
      case _: StashOverflowException =>
        log.warning("Entity too Busy, bouncing {}", msg)
        sender() ! EntityTooBusy
    }
  }

  //  // TODO remove this again after debugging
  //  case object InternalEntityTooBusy
  //
  //  // should only be called by persistence stash
  //  // TODO could be val
  //  override val internalStashOverflowStrategy: StashOverflowStrategy = {
  //    log.debug("internalStashOverflowStrategy called, should only happen by persistence")
  //    ReplyToStrategy(InternalEntityTooBusy)
  //  }

  override def aroundPostRestart(reason: Throwable): RebelSuccess = {
    log.error("Super weird stuff happening, restarting because of {}", reason)
    if (reason.isInstanceOf[StashOverflowException]) {
      log.error("Weird stuff happening because of stash... probably because Persistent FSM uses stash, but just crashes when full... " +
        "TODO fix this, supervisor strategy? Or increase stash size...")
    }
    super.aroundPostRestart(reason)
  }

  protected def getOrCreateParticipant(transactionId: TransactionId): ParticipantRef = {
    log.debug("activeTransactionIds: {} childactors: {}", activeTransactions, context.children)
    val coordinator = TwoPhaseCommitManagerSharding.contactPoint(transactionId)

    val maybeParticipant: Option[ParticipantRef] = context.child(participantActorName(transactionId))
    // create participant actor if not yet existing
    maybeParticipant.getOrElse {
      val participant: ParticipantRef = context.actorOf(TransactionParticipant.props(ActorRefContactPoint(self), coordinator),
        participantActorName(transactionId))
      log.debug("created new TransactionParticipant for {}: {}", transactionId, participant.path.toStringWithoutAddress)
      //    transactions  = Some(transactionId)
      publishMessageEvent(CommentEvent(self, s"new participant actor: $transactionId, children:${context.children.map(_.path.elements.last)}"))
      participant
    }
  }
}