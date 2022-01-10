package com.ing.rebel.sync.pathsensitive

import cats.Eq
import cats.data.Validated.Invalid
import cats.data.{Chain, NonEmptyChain, NonEmptyList, Validated}
import cats.implicits._
import cats.kernel.Semigroup
import com.ing.rebel
import com.ing.rebel.RebelError.{StaticDependentError, SyncFailed}
import com.ing.rebel.config._
import com.ing.rebel.specification.{RebelSpecification, Specification}
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Delay._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Accept._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.Reject._
import com.ing.rebel.sync.pathsensitive.AllowCommandDecider.{AllowCommandDecision, _}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.SyncOperation
import com.ing.rebel.{RebelCheck, RebelConditionCheck, RebelDomainEvent, RebelError, RebelErrors, RebelState, RebelSuccess, SpecificationEvent}

object AllowCommandDecider {
  sealed trait AllowCommandDecision
  sealed trait Accept extends AllowCommandDecision // Direct accept the event, potentially bootstrapping 2PC
  // Direct Decline, fail fast the event, short circuit 2PC
  object Accept {
    case object StaticAccept extends Accept // Accepted with static knowledge
    case object DynamicAccept extends Accept // Accepted with dynamic knowledge (using possible outcomes)
  }
  case class Reject(reasons: NonEmptyChain[RejectReason]) extends AllowCommandDecision
  object Reject {
    sealed trait RejectReason
    case object StaticRejectReason extends RejectReason
    case class DynamicRejectReason(errors: RebelErrors) extends RejectReason

    //helpers
    val StaticReject: Reject = Reject(NonEmptyChain(StaticRejectReason))

    // case class does not work, because not allowed to extend case class with case class
    def DynamicReject(errors: RebelErrors): Reject = Reject(NonEmptyChain(DynamicRejectReason(errors)))

  }
  // Delay, fall back to waiting/locking, vanilla 2PC
  case class Delay(reasons: NonEmptyChain[DelayReason], rejectReasons: Set[RejectReason]) extends AllowCommandDecision
  object Delay {
    sealed trait DelayReason
    case class FunctionalDelayReason(errors: RebelErrors) extends DelayReason // Delayed because of incompatibility with other in progress action
    case object TwoPLLockDelayReason extends DelayReason // TwoPC only allows one parallel transaction
    case object MaxInProgressReachedDelayReason extends DelayReason // Configured max reached
    //helpers
    val TwoPLLockDelay: Delay = Delay(NonEmptyChain(TwoPLLockDelayReason), Set.empty)
    val MaxInProgressReachedDelay: Delay = Delay(NonEmptyChain(MaxInProgressReachedDelayReason), Set.empty)

    def FunctionalDelay(errors: RebelErrors): Delay = Delay(NonEmptyChain(FunctionalDelayReason(errors)), Set.empty)
  }

  /**
    * Ways to combine `AllowCommandDecision`. combines error/delay messages
    * Accept + Reject => Reject
    * Accept + Delay => Delay
    * TODO check if this is correctly implemented
    */
  implicit val psacAllowCommandDecisionSemigroup: Semigroup[AllowCommandDecision] = {
    val comb: PartialFunction[(AllowCommandDecision, AllowCommandDecision), AllowCommandDecision] = {
      case (a1: Accept, _: Accept) => a1
      case (_: Accept, r: Reject)  => FunctionalDelay(RebelErrors.of(SyncFailed(s"Dependent events")))
      //      case (r1: DynamicReject, r2: DynamicReject)     => DynamicReject(r1.errors combine r2.errors)
      //      case (r1: DynamicReject, r2: Reject)            => r1 // TODO information is lost, r2
      //      case (r1: DynamicReject, _: Accept)             => r1 // TODO information is lost, r2
      //      case (r1: DynamicReject, _: Delay)              => r1 // TODO information is lost, r2
      //      case (r1: Reject, r2: DynamicReject)            => r2 // TODO information is lost, r1
      case (r1: Reject, r2: Reject) => Reject(r1.reasons ++ r2.reasons)
      case (r: Reject, d: Delay)    => Delay(d.reasons, r.reasons.toList.toSet)
      case (_: Accept, d: Delay)    => d
      case (d1: Delay, d2: Delay)   => Delay(d1.reasons ++ d2.reasons, d1.rejectReasons ++ d2.rejectReasons)
      //      case (r1: FunctionalDelay, r2: FunctionalDelay) => FunctionalDelay(r1.errors combine r2.errors)
      //      case (r1: FunctionalDelay, r2: Delay)           => r1 // TODO information is lost, r2
      //      case (r1: FunctionalDelay, _: Accept)           => r1 // TODO information is lost, r2
      //      case (r1: Delay, r2: FunctionalDelay)           => r2 // TODO information is lost, r1
      //      case (r1: Delay, r2: Delay)                     => r1
      //      case (r1: Delay, r2: Accept)                    => r1
      //    case (left: AllowCommandDecision, right: AllowCommandDecision) => combine(right, left)
    }
    val combAssoc: PartialFunction[(AllowCommandDecision, AllowCommandDecision), AllowCommandDecision] =
      comb.orElse { case (left, right) => comb(right, left) }
    (x: AllowCommandDecision, y: AllowCommandDecision) => combAssoc(x, y)
  }


  def allowCommandDecider[S <: Specification](config: RebelConfig, specification: RebelSpecification[S]): AllowCommandDecider[S] =
    config.sync.commandDecider match {
      case Locking           => new TwoPLCommandDecider(specification)
      case Dynamic           => new DynamicPsacCommandDecider[S](config.sync.maxTransactionsInProgress, specification)
      case StaticThenDynamic => new StaticCommandDecider[S](config.sync.maxTransactionsInProgress, specification)()
      case StaticThenLocking => new StaticCommandDecider[S](config.sync.maxTransactionsInProgress, specification)(new TwoPLCommandDecider(specification))
    }
}

/**
  * Decides if command is allowed to start at this moment
  *
  * @tparam S spec
  */
trait AllowCommandDecider[S <: Specification] {
  def allowCommand(currentState: S#State, currentData: S#RData,
                   relevantTransactions: Seq[S#RDomainEvent], incomingEvent: S#RDomainEvent): AllowCommandDecision
}

class TwoPLCommandDecider[S <: Specification](specification: RebelSpecification[S]) extends AllowCommandDecider[S] {
  def allowCommand(currentState: specification.RState, currentData: specification.RData,
                   relevantTransactions: Seq[specification.RDomainEvent], incomingEvent: specification.RDomainEvent): AllowCommandDecision = {
    if (relevantTransactions.nonEmpty) {
      // "2PC does not support in progress transactions"
      TwoPLLockDelay
    } else {
      (specification.nextStateGeneric(currentState, incomingEvent.specEvent) <*
        specification.checkPreConditions(currentData, incomingEvent.timestamp)(incomingEvent.specEvent))
        .fold(DynamicReject, _ => DynamicAccept)
    }
  }
}

class StaticCommandDecider[S <: Specification](maxTransactionsInProgress: Int, specification: RebelSpecification[S])
                                              (fallBackCommandDecider: AllowCommandDecider[S] = new DynamicPsacCommandDecider(maxTransactionsInProgress, specification))
  extends AllowCommandDecider[S] {

  override def allowCommand(currentState: specification.RState, currentData: specification.RData,
                            relevantTransactions: Seq[specification.RDomainEvent], incomingEvent: specification.RDomainEvent): AllowCommandDecision = {
    // If no actions in progress
    if (relevantTransactions.isEmpty) {
      specification.tryTransition(currentState, currentData, incomingEvent).fold(DynamicReject, _ => DynamicAccept): AllowCommandDecision
    } else {
      // check if compatible with all in progress actions
      val compatibleWithAllInProgress: Boolean =
        relevantTransactions.forall(inProgressAction => specification.alwaysCompatibleEvents.isDefinedAt(inProgressAction.specEvent, incomingEvent.specEvent))

      if (compatibleWithAllInProgress) {
        // all are compatible
        StaticAccept
        // check if incompatible with all to fail fast
      } else if (relevantTransactions.forall(inProgressAction => specification.failFastEvents.isDefinedAt(inProgressAction.specEvent, incomingEvent.specEvent))) {
        StaticReject
      } else {
        // not always allowed, and not declined fast, falling back to checking all states
        fallBackCommandDecider.allowCommand(currentState, currentData, relevantTransactions, incomingEvent)
      }
    }
  }
}

class DynamicPsacCommandDecider[S <: Specification](maxTransactionsInProgress: Int, specification: RebelSpecification[S]) extends AllowCommandDecider[S] {

  /**
    * Types often used
    */
  private object Types {
    // Grouping of state and data
    type RelevantState = (specification.RState, specification.RData)
    // function that takes old state and computes new state given event
    type EventEffector = (RelevantState, specification.RDomainEvent) => RelevantState
    // Possible outcome "tree", storing only the leaves
    type PossibleOutcomes = NonEmptyChain[RelevantState]
  }

  import Types._

  private val eventEffector: EventEffector =
    (state, event) => {
      val newState: RebelCheck[(specification.RState, specification.RData)] =
        specification.tryTransition(state._1, state._2, event)
      newState.getOrElse {
        throw new NotImplementedError(
          s"Should not occur for $event $newState in currentState/Data/syncOps: ${state._1}/${state._2}")
      }
    }

  /**
    * Calculates new possible outcomes, given current possible outcomes. Since the Possible Outcome Tree branches
    * into one where the event is applied and one where not, we can keep the old list of outcome states and add a version
    * with states where the event is applied
    */
  private def newPossibleOutcomes(outcomes: PossibleOutcomes, event: specification.RDomainEvent): PossibleOutcomes = {
    outcomes.combine(outcomes.map(state => eventEffector(state, event)))
  }

//  def isSameSyncOperation(a: SyncOperation, b: SyncOperation): Boolean = {
//    a.participantId == b.participantId && {
//      (a.syncEvent, b.syncEvent) match {
//        case (RebelDomainEvent(specEventA, timestampA, _), RebelDomainEvent(specEventB, timestampB, _)) => specEventA == specEventB &&  timestampA == timestampB
//        case (_: rebel.QuerySyncEvent[_],_: rebel.QuerySyncEvent[_]    )                        => true
//        case _ => false
//      }
//    }
//  }

  private def isActionAllowedInState(/* eventLeadingToRelevantState: specification.RDomainEvent, */ relevantState: RelevantState,
                                     incomingEvent: specification.RDomainEvent, syncs: Set[SyncOperation])
  : AllowCommandDecision = {
    // First see if transition is valid
    val (state, data) = relevantState
    val relevantOutcomeState: RebelCheck[(specification.RState, specification.RData)] =
      specification.tryTransition(state, data, incomingEvent)

    // If valid, then check if sync operations differ from sync operations in current state, else fall back to delay
    val syncOpsCheck: RebelCheck[AllowCommandDecision] =
      specification.syncOperationsGeneric(data, incomingEvent).map { syncOps: Set[SyncOperation] =>
        if (syncOps === syncs) {
          DynamicAccept
        } else {
          FunctionalDelay(RebelErrors.of(
            SyncFailed(s"Dependent events (<in progress event>, $incomingEvent), because Sync Operations changed: $syncs != $syncOps")))
        }
      }
    // map errors to Reject
    (relevantOutcomeState *> syncOpsCheck).valueOr(DynamicReject)
  }

  /**
    * allowCommand checks whether the incomingEvent is valid in all possible outcome states.
    * It also makes sure that the sync operations are not different, because this would change the involved participants or their sync event
    */
  override def allowCommand(currentState: specification.RState, currentData: specification.RData,
                            relevantTransactions: Seq[specification.RDomainEvent], incomingEvent: specification.RDomainEvent): AllowCommandDecision = {
    if (relevantTransactions.size >= maxTransactionsInProgress) {
      // Delay when configured maximum parallelism is reached
      MaxInProgressReachedDelay
    } else {
      // Algorithm:
      // Iterate over all possible state/data outcomes. Try apply command & make sure sync operations do not changes by effects of in progress actions
      // All OK => Accept / OK
      // All NOK => Reject / NOK
      // otherwise => Delay / stash
      // Future work: reorder

      val initialPossibleOutcomes: PossibleOutcomes = NonEmptyChain.one((currentState, currentData))

      // compute all possible outcomes for all in progress event
      val possibleOutcomes: PossibleOutcomes = relevantTransactions.foldLeft(initialPossibleOutcomes) {
        // TODO we can cache newPossibleOutcomes, because start of list will be the same if no actions completed in the mean time since previous check
        case (outcomes, event) => newPossibleOutcomes(outcomes, event)
      }

      // Compute set of sync operations for new event in current state
      // Empty Set is safe because isActionAllowedInState would fail anyway
      val syncOperationsIncomingEvent: Set[SyncOperation] = specification.syncOperationsGeneric(currentData, incomingEvent).getOrElse(Set())
      // For all possible action check if incoming event results in Accept/Reject/Delay
      val newActionInTentativeStates: NonEmptyChain[AllowCommandDecision] =
        possibleOutcomes.map(isActionAllowedInState(_, incomingEvent, syncOperationsIncomingEvent))

      val allOk: Boolean = newActionInTentativeStates.forall(_.isInstanceOf[Accept])
      if (allOk) {
        // direct accept
        DynamicAccept
      } else {
        val allReject: Boolean = newActionInTentativeStates.forall(_.isInstanceOf[Reject])
        // uses SemiGroup defined above, not that allReject case is covered by SemiGroup as well. Kept for code readability
        val rejectOrDelay: AllowCommandDecision = newActionInTentativeStates.reduce
        if (allReject) {
          // direct reject
          assert(rejectOrDelay.isInstanceOf[Reject], s"If not all Accept and all Reject, there should be at least one Reject/error message: $rejectOrDelay")
          rejectOrDelay
        } else {
          // delay
          assert(rejectOrDelay.isInstanceOf[Delay], s"If not all Accept and not all Reject, there should be at least one Delay message: $rejectOrDelay")
          rejectOrDelay
        }
      }
    }
  }
}