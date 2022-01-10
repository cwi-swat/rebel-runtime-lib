package com.ing.rebel.specification

import cats.data.Validated
import com.github.nscala_time.time.Imports.DateTime
import com.ing.rebel.RebelError.{CommandNotAllowedInState, GenericRebelError, PostConditionFailed, PreConditionFailed}
import com.ing.rebel.messages.{CurrentState, RebelCommand}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Initialize, SyncAction, SyncOperation, TransactionId}
import com.ing.rebel.{RebelCheck, RebelConditionCheck, RebelData, RebelState, RebelSuccess, Uninitialised, _}
import io.circe.Encoder
import org.joda.time.DateTime

import scala.reflect.ClassTag

trait SpecificationInfo[+T <: Specification] {
  def spec: T
}

object Specification {
  def getSpec[T <: Specification](implicit info: SpecificationInfo[T]): T = info.spec
}

/**
  * Helper class to create Specification instance, not having to define the implicits yourself when overriding
  * One caveat is that you need to define your types before/outside the object that is being used for the specification
  *
  * Unused at the moment, because placing them in different objects results in many automatic implicit search not working any more
  *
  * @param dEncoder
  * @param sEncoder
  * @param k
  * @tparam E
  * @tparam D
  * @tparam S
  * @tparam K
  */
abstract class SpecificationHelper[E <: SpecificationEvent, D, S <: RebelState, K]
(implicit dEncoder: Encoder[D], sEncoder: Encoder[S], k: RebelKeyable[K]) extends Specification {
  override type Event = E
  override type Data = D
  override type State = S
  override type Key = K

  override def keyable: RebelKeyable[K] = k

  override implicit val dataEncoder: Encoder[D] = dEncoder
  override implicit val stateEncoder: Encoder[S] = sEncoder
}

class EventLink[-Event <: SpecificationEvent, S <: Specification]
class StateLink[-State <: RebelState, S <: Specification]
class DataLink[-Data, S <: Specification]

trait Specification {
  self =>
  type Event <: SpecificationEvent
  type Data
  type State <: RebelState
  type Key

  implicit val specInfo: SpecificationInfo[this.type] = new SpecificationInfo[this.type] {
    override def spec: self.type = self
  }

  def keyable: RebelKeyable[Key] // leave unimplemented for now = macro Macros.keyable[Key]

  type RData = RebelData[Data]
  type RDomainEvent = RebelDomainEvent[this.type]
  //  implicit val m2p: this.RDomainEvent =:= RebelDomainEvent[this.type] = implicitly
  implicit val p2m: RebelDomainEvent[this.type] =:= this.RDomainEvent = implicitly

  /**
    * Default implementation, assumes object
    * trims trailing $
    */
  val specificationName: String = this.getClass.getSimpleName.replaceAll("\\$$", "")

  implicit val eventLink: EventLink[Event, this.type] = new EventLink[Event, this.type]
  implicit val stateLink: StateLink[State, this.type] = new StateLink[State, this.type]
  implicit val dataLink: DataLink[Data, this.type] = new DataLink[Data, this.type]

  implicit val dataEncoder: Encoder[Data]
  implicit val rDataEncoder: Encoder[RData] = io.circe.generic.semiauto.deriveEncoder
  implicit val stateEncoder: Encoder[State]
  implicit val currentStateEncoder: Encoder[CurrentState[this.type]] = io.circe.generic.semiauto.deriveEncoder
}

/**
  * Generic trait that describes the Rebel specification. Abstracted from the Akka implementation.
  */
trait RebelSpecification[S <: Specification] {
  //trait RebelSpecification[RState <: RebelState, Data, SpecEvent <: SpecificationEvent] {
  type Spec = S

  type RState = S#State
  type RData = S#RData
  type RDomainEvent = S#RDomainEvent
  implicit val ev: RDomainEvent =:= S#RDomainEvent = implicitly

  type SpecEvent = S#Event
  //  type RCommand = RebelCommand[SpecEvent]
  /**
    * Given current state and command, determines state that can be reached
    *
    * @return the deterministic next state
    */
  def nextState: PartialFunction[(RState, SpecEvent), RState]

  def checkPreConditions(data: RData, now: DateTime): PartialFunction[SpecEvent, RebelConditionCheck]

  def applyPostConditions(data: RData, domainEvent: RDomainEvent): RData

  def checkPostConditions(currentStateData: RData, now: DateTime, projectedStateData: RData): PartialFunction[SpecEvent, RebelConditionCheck]

  def invariantsHold(projectedStateData: RData): RebelConditionCheck

  /**
    *
    * @return a set of sync operations or an error if no syncs defined for this domainEvent or state not initialized
    */
  def syncOperations(now: DateTime, transactionId: TransactionId): PartialFunction[(SpecEvent, RData), Set[SyncOperation]]

  val noEventsWithSyncs: PartialFunction[(SpecEvent, RData), Set[SyncOperation]] = {
    case _ => Set()
  }

  def syncOperationsGeneric(data: RData, domainEvent: RDomainEvent): RebelCheck[Set[SyncOperation]] = {
    val syncOps: Option[Set[SyncOperation]] =
      syncOperations(domainEvent.timestamp, domainEvent.transactionId).lift.apply(domainEvent.specEvent, data)
    // make sure the same transaction id as parent transaction is used
    val syncOpsWithCurrentTransactionId: Option[Set[SyncOperation]] = syncOps.map(_.map(
      s => s.copy(participantId = s.participantId.copy(transactionId = domainEvent.transactionId), syncEvent = s.syncEvent match {
        case r: RebelDomainEvent[_]   => r.copy(transactionId = domainEvent.transactionId, timestamp = domainEvent.timestamp)
        case event: QuerySyncEvent[_] => event
      })
    ))
    Validated.fromOption(syncOpsWithCurrentTransactionId,
      GenericRebelError(s"State not initialised or syncOperations not defined for event `$domainEvent` and data `$data`")).toValidatedNec
  }

  /**
    * A function that takes an in progress event and an incoming event, and return if the incoming event can safely be started without checking pre-conditions.
    *
    * @return true if incoming event can be started without checks
    */
  def alwaysCompatibleEvents: PartialFunction[(S#Event, S#Event), Boolean]

  /**
    * A partial function that takes an in progress event and an incoming event, and return if the incoming event can be directly declined without checking pre-conditions.
    *
    * @return true if incoming event can be declined fast without checks
    */
  def failFastEvents: PartialFunction[(S#Event, S#Event), Boolean]

  def initialState: RState = {
    throw new NotImplementedError(
      "Please implement `uninitializedState` with lazy val or def in order to make sure the superclass constructor" +
        " can find a value. See http://docs.scala-lang.org/tutorials/FAQ/initialization-order.html for details")
  }

  // TODO Might be able to do this with scala reflect: `ClassSymbol.knownDirectSubclasses` or macros
  def allStates: Set[RState] = {
    throw new NotImplementedError(
      "Please implement `allStates` with lazy val or def in order to make sure the superclass constructor" +
        " can find a value. See http://docs.scala-lang.org/tutorials/FAQ/initialization-order.html for details")
  }

  def finalStates: Set[RState]

  def initialData: RData = Uninitialised

  // Helper functions
  def nextStateGeneric(currentStateLabel: RState, command: SpecEvent): RebelCheck[RState] = {
    val label: Option[RState] = nextState.lift.apply(currentStateLabel, command)
    Validated.fromOption(label, CommandNotAllowedInState(currentStateLabel, command)).toValidatedNec
  }

  private def checkCondition(cond: => Boolean, failureMessage: RebelError): RebelConditionCheck =
    try {
      Validated.condNec(cond, (), failureMessage)
    } catch {
      case exception: Exception => Validated.invalidNec[RebelError, RebelSuccess](GenericRebelError(exception))
    }

  def checkPreCondition(cond: => Boolean, failureMessage: String): RebelConditionCheck =
    checkCondition(cond, PreConditionFailed(failureMessage))

  def checkPostCondition(cond: => Boolean, failureMessage: String): RebelConditionCheck =
    checkCondition(cond, PostConditionFailed(failureMessage))


  /**
    * Given a state, data and event returns a valid new state and data, or a non-empty list of errors
    *
    * @param state
    * @param data
    * @param event
    * @return
    */
  def tryTransition(state: RState, data: RData, event: RDomainEvent): RebelCheck[(RState, RData)] = {
    import cats.implicits._
    // Determine next state
    (nextStateGeneric(state, event.specEvent) <*
      // check preconditions
      checkPreConditions(data, event.timestamp)(event.specEvent))
      .map { projectedState: RState =>
        val projectedData = applyPostConditions(data, event)
        (projectedState, projectedData)
      }
    //      .andThen {
    //      projectedState: RState =>
    // determine new state
    //        val projectedData = applyPostConditions(data, event)
    // check if post conditions hold, disabled for now, because not really needed
    // TODO add post condition check to (generated) test cases
    //        val postCheck: RebelConditionCheck = checkPostConditions(data, event.timestamp, projectedData)(event.specEvent)
    //        postCheck.map(_ => (projectedState, projectedData))
    //        (projectedState, projectedData)
    //    }
  }
}