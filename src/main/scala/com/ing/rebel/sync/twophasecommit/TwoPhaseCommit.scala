package com.ing.rebel.sync.twophasecommit

import com.ing.rebel.messages._
import com.ing.rebel.specification.Specification
import com.ing.rebel.{InState, IsInitialized, QuerySyncEvent, RebelConditionCheck, RebelDomainEvent, RebelErrors, RebelState, RebelSyncEvent, SpecificationEvent, messages}

import scala.reflect.ClassTag

/**
  * 2 Phase Commit implementation based on algorithm as described in "Distributed Systems" by Van Steen and Tanenbaum
  * Improved with nested participants (Transactional Information Systems, chapter 19)
  */
object TwoPhaseCommit {
  case class TransactionId(transactionId: String) extends AnyVal {
    override def toString: String = transactionId
  }
  case class ParticipantId(transactionId: TransactionId, participantId: String) {
    override def toString: String = s"$transactionId-$participantId"
  }

  // participantId to make sure that locking is done in increasing order to prevent deadlocks
  // + unique identifier for participant
  case class SyncAction[S <: Specification](participantId: ParticipantId, contactPoint: ContactPoint, syncEvent: RebelSyncEvent[S]) {
//    def apply(participantId: ParticipantId, contactPoint: ContactPoint, syncEvent: RebelSyncEvent[S] : SyncAction[S] =
//      this(participantId, contactPoint, syncEvent)
  }
  object SyncAction {
    def syncInitialized[S <: Specification](participantId: ParticipantId, transactionId: TransactionId, contactPoint: ContactPoint): SyncAction[S] =
      SyncAction(participantId, contactPoint, IsInitialized(transactionId))

    def syncInState[S <: Specification](participantId: ParticipantId, transactionId: TransactionId, contactPoint: ContactPoint, state: S#State): SyncAction[S] =
      SyncAction(participantId, contactPoint, InState(transactionId, state))

    // Ordering to be really sure that the order of participants is always the same, for all transactions, to avoid deadlocks
    implicit val contactPointOrdering: Ordering[ContactPoint] = Ordering.by {
      case ShardingContactPoint(typeName, key) => typeName ++ key.toString
      case ActorRefContactPoint(actorRef)      => actorRef.path.toStringWithoutAddress
    }
    implicit val syncActionOrdering: Ordering[SyncOperation] = Ordering.by(_.contactPoint)
  }
  // Wider type to also include SyncOperations such as InState, IsInitialized
  type SyncOperation = SyncAction[_ <: Specification]
//  type SyncSpecEvent = SyncAction[RebelDomainEvent[SpecificationEvent]]

  sealed trait Command extends RebelInternalMessage {
    def transactionId: TransactionId
  }

  // `participants` is still needed for initial participant
  case class Initialize private(transactionId: TransactionId, participants: Set[SyncOperation]) extends Command
  case class VoteRequest(transactionId: TransactionId, participantId: ParticipantId, request: RebelSyncEvent[_ <: Specification]) extends Command
  case class VoteCommit(transactionId: TransactionId, participantId: ParticipantId, nestedParticipants: Set[SyncOperation]) extends Command
  case class VoteAbort(transactionId: TransactionId, participantId: ParticipantId, reasons: RebelErrors) extends Command
  case class GlobalCommit(transactionId: TransactionId) extends Command
  case class GlobalCommitAck(transactionId: TransactionId, participantId: ParticipantId) extends Command
  case class GlobalAbort(transactionId: TransactionId) extends Command
  case class GlobalAbortAck(transactionId: TransactionId, participantId: ParticipantId) extends Command
  case class TransactionResult(transactionId: TransactionId, result: RebelConditionCheck) extends Command
}
