package com.ing.rebel.actors

import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import cats.data.Validated
import cats.implicits._
import com.ing.rebel.RebelError.{PreConditionFailed, SyncFailed}
import com.ing.rebel.actors.RebelFSMActor.StopInFinalState
import com.ing.rebel.messages.{ProcessEvent, _}
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.sync.twophasecommit.{ActorRefContactPoint, EntityHost}
import com.ing.rebel.util.MessageLogger.MessageEvent
import com.ing.rebel.{RebelData, RebelError, RebelState, _}
import io.circe.Encoder
import scala.collection.immutable.Seq

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.reflect.ClassTag

object RebelFSMActor {
  case object StopInFinalState extends RebelInternalMessage
}

abstract class RebelFSMActor[S <: Specification](implicit val stateEncoder: Encoder[S#State],
                                                 implicit val dataEncoder: Encoder[S#Data],
                                                 implicit val eventEncoder: Encoder[S#Event])
  extends PersistentActor
    with RebelActor[S]
    with EntityHost[S] {
  specification: RebelSpecification[S] =>

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher

  var currentState: RState = initialState
  var currentData: RData = initialData

  type State = (RState, RData)

  // Enable this to disable initial persistence id check
  //  override def recovery: Recovery = Recovery.none

  /**
    * Version of sync implementation that uses the 2PC protocol
    */
  def create2PCInitialize(domainEvent: RDomainEvent)(data: RData): RebelCheck[Initialize] =
    RebelCheck.success(Initialize(domainEvent.transactionId, Set(create2PCSelfSyncCommand(domainEvent))))

  def success2PCInitialize(domainEvent: RDomainEvent): Initialize =
    Initialize(domainEvent.transactionId, Set[SyncOperation]())

  def create2PCSelfSyncCommand(domainEvent: RDomainEvent): SyncAction[S] = {
    // always do the command on self
    val pId = ParticipantId(domainEvent.transactionId, UniqueId("p"))
    val sa: SyncAction[S] = SyncAction(pId,
      // TODO use Sharding here. But needs to know own identity for this.
      ActorRefContactPoint(self), domainEvent)
    sa
  }

  override def receiveCommand: Receive = LoggingReceive(processEventReceive orElse genericReceive)

  val processEventReceive: Receive = {
    case pe: ProcessEvent[S] => processEvent(pe)
  }

  private def processEvent(processEvent: ProcessEvent[S]): Unit = {
    val domainEvent: RDomainEvent = processEvent.event
    val transition: RebelCheck[(RState, RData)] = tryTransition(currentState, currentData, domainEvent)

    transition.fold(
      errors => {
        log.warning("Command {} rejected while in state {} because: {}", domainEvent, currentState, errors)
        sender() ! EntityCommandFailed(domainEvent, errors)
      }, { case (nextState, nextData) =>
        // If everything valid until now, do sync!
        // just do the action, because sync is handled by Host
        log.debug("Command success: {} + {} -> {} {}", currentState, domainEvent.specEvent, nextState, nextData)
        // TODO move away from persistent FSM
        persist(domainEvent) { dEvent =>
          applyNewState(applyEvent(dEvent))
          //          goto(nextState) applying domainEvent andThen { newData =>
          log.debug("Persisted and in to state {} with {}", nextState, nextData)
          val success = EntityCommandSuccess(dEvent)
          sender() ! success
          //            log.debug("Persisted and in to state {} with {}", nextState, newData)
          //            updateVisualizationState(s"$nextState [$newData]")
          publishMessageEvent(MessageEvent(self, sender(), success.toString))
          if (finalStates.contains(nextState)) {
            log.debug("Stopping, because in final state: {}", nextState)
            context.system.scheduler.scheduleOnce(2.seconds)(self ! StopInFinalState)
          }
        }
      })
  }

  protected def processEvents(processEvents: Seq[ProcessEvent[S]]): Unit = {
    persistAll(processEvents.map(_.event)) { dEvent : RDomainEvent =>
      applyNewState(applyEvent(dEvent))
      // currentState should be the updated state now
      log.debug("Persisted and in to state {} with {}", currentState, currentData)
      if (finalStates.contains(currentState)) {
        log.debug("Stopping, because in final state: {}", currentState)
        context.system.scheduler.scheduleOnce(2.seconds)(self ! StopInFinalState)
      }
    }
  }

  implicit val currentStateEncoder: Encoder[CurrentState[S]] = {
    import com.ing.rebel._
    import io.circe.generic.auto._
    io.circe.generic.semiauto.deriveEncoder
  }

  private def isInitialized(state: RState) = state == initialState || finalStates.contains(state)

  // Used for generic/other messages
  val genericReceive: Receive = {
    // When in final state stop
    case StopInFinalState =>
      if (!finalStates.contains(currentState)) {
        log.warning("Stopping RebelFSM, but not in final state: {}", currentState)
      }
      context.stop(self)

    case TellState(_) =>
      sender() ! CurrentState(currentState, currentData)

    case cp@CheckPreconditions(syncEvent: RDomainEvent, _) =>
      log.debug("Handling {}", cp)
      val nsg = specification.nextStateGeneric(currentState, syncEvent.specEvent)
      val check = specification.checkPreConditions(currentData, syncEvent.timestamp)(syncEvent.specEvent)
      val sos = syncOperationsGeneric(currentData, syncEvent)
      // check if next state is allowed and its preconditions
      val conditionCheck: RebelCheck[Set[SyncOperation]] =
        nsg *>
          check *>
          sos.map(_.map {
            // make sure the original transaction id is used for nested events, extra isInstanceOf due to type erasure
            so =>
              val newSyncEvent = so.syncEvent match {
                case rde@RebelDomainEvent(specEvent, timestamp, transactionId) =>
                  rde.copy(transactionId = syncEvent.transactionId)
                case q: QuerySyncEvent[S]                                      => q
              }
              so.copy(syncEvent = newSyncEvent)
          })

      sender() ! CheckPreconditionsResult(syncEvent.transactionId, conditionCheck)
      log.debug("CheckPreconditions [{}] -> result {}", cp, conditionCheck)

    case cp@CheckPreconditions(IsInitialized(tId), _) =>
      val result: CheckPreconditionsResult = CheckPreconditionsResult(tId,
        if (isInitialized(currentState)) {
          RebelConditionCheck.failure(PreConditionFailed(s"IsInitialized failed: State is in initial state `$initialState`"))
        } else {
          Validated.valid(Set())
        })
      sender() ! result
      log.debug("Received `{}` from {}. Result: {}", cp, sender(), result)

    case cp@CheckPreconditions(InState(tId, state), _) =>
      sender() ! CheckPreconditionsResult(tId,
        if (currentState == state) {
          Validated.valid(Set())
        } else {
          RebelConditionCheck.failure(PreConditionFailed(s"State is in state `$state`, instead of `$currentState`"))
        })

    case cp@CheckPreconditions(operation, _) =>
      val errorMessage = s"Received CheckPreconditions command `$cp`, meant for other specification"
      log.error(errorMessage)
      sender() ! CheckPreconditionsResult(operation.transactionId, RebelConditionCheck.failure(SyncFailed(errorMessage)))

    case InState(tId, state) =>
      assert(assertion = false, "InState should never be called directly, only as part of sync block")
    //      sender() ! InStateResult(stateName == state)
    case c@(_: SpecEvent | _: RebelDomainEvent[_]) =>
      throw new UnsupportedOperationException(s"Rebel commands ($c) can no longer direct be send to entities")
    case msg                                       =>
      log.warning("Message [{}] not allowed/unknown when in state [{}]", msg, currentState)
    //      sender() ! new UnsupportedOperationException(")
  }

  override val persistenceId: String = s"$entityTypeName-${self.path.name}"

  def applyNewState(newState: State): Unit = {
    currentState = newState._1
    currentData = newState._2
  }

  override val receiveRecover: Receive = {
    case event: RDomainEvent               => applyNewState(applyEvent(event))
    case SnapshotOffer(_, snapshot: State) => applyNewState(snapshot)
    case RecoveryCompleted                 => ()
    case other                             => throw new Exception(s"Unknown recover message received $other")
  }

  def applyEvent(domainEvent: RDomainEvent): State = {
    domainEvent match {
      case rde: RebelDomainEvent[_] =>
        val newData = specification.applyPostConditions(currentData, rde)
        log.debug("{}playing applyEvent({}, {}) in state {}", if (recoveryRunning) "Re" else "", domainEvent, currentData)
        val newState = nextState(currentState, rde.specEvent)
//        updateVisualizationState(s"$newState [$newData]")
        (newState, newData)
    }
  }

  // TODO reintroduce?
  //  if (visualisationEnabled) {
  //    onTransition({
  //      case (old, newState) =>
  //        updateVisualizationState(s"$newState [$nextStateData]")
  //        publishMessageEvent(CommentEvent(self, s"$newState [$nextStateData]"))
  //
  //    })
  //  }
}