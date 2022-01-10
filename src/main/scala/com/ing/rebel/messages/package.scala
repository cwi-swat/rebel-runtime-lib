package com.ing.rebel

import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.specification.{DataLink, Specification, StateLink}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Initialize, SyncOperation, TransactionId}
import io.circe.{Encoder, Json}

/**
  * Package object containing all rebel messages
  */
package object messages {
  /**
    * This is a super trait for Rebel messages, it is used in the configuration to use an efficient serializer.
    */
  sealed trait RebelMessage extends SerializeWithKryo
  /**
    * Event wrapper to issue a state changing command to an Entity
    *
    * @param domainEvent
    * @tparam S
    */
  final case class RebelCommand[S <: Specification](domainEvent: S#RDomainEvent) extends RebelMessage

  /**
    * Message to really do this event/process this event
    *
    * @param event
    * @tparam S
    */
  final case class ProcessEvent[S <: Specification](event: S#RDomainEvent) extends RebelMessage
  // TODO rename
  final case class ReleaseEntity[S <: Specification](event: RebelSyncEvent[S], process: Boolean) extends RebelMessage

  /**
    * All query's that can be asked an entity, not changing any state
    */
  sealed trait RebelQuery extends RebelMessage {
    def after: Option[TransactionId]
  }

  /**
    * Responses to RebelQueries
    */
  sealed trait RebelResponse extends RebelMessage

  /**
    * Internal message, not meant for consumers/outside
    */
  trait RebelInternalMessage extends RebelMessage

  // Actual messages

  /**
    * Query the entity's state
    *
    * @param after Optional transaction id, which should be finished/handled in the response, could result in delay of response
    */
  final case class TellState(after: Option[TransactionId] = None) extends RebelQuery

  object TellState {
    def apply(after: TransactionId): TellState = new TellState(Some(after))
  }
  /**
    * CurrentState class normally used, but can only be constructed using CurrentState.apply,
    * which takes care of the correct toJson implementation using circe
    *
    * @param state RebelState
    * @param data  RebelData
    */
  case class CurrentState[S <: Specification] private(state: S#State, data: S#RData) extends RebelResponse
  object CurrentState {
    def apply[State <: RebelState, Data, S <: Specification](state: State, data: RebelData[Data])
             (implicit link: StateLink[State, S], evidence: State <:< S#State,
              dataLink: DataLink[Data, S], dataEvidence: RebelData[Data] =:= S#RData //, ev2: Data =:= S#Data
             ): CurrentState[S] = new CurrentState(state, data)
  }

//  object CurrentState {
  //  ////    case class CurrentStateInternal[RState <: RebelState, RData] private(state: RState, data: RebelData[RData])
  //  ////                                                                        (implicit encoder: Encoder[CurrentStateInternal[RState, RData]])
  //  ////      extends CurrentState(state, data) {
  //  ////      // val to make sure encoder is never serialized. This might impact performance because encoder is run on every creation of CurrentState
  //  ////      override val json: Json = encoder.apply(this)
  //  ////    }co
  //  ////
  //  ////    def apply[RState <: RebelState, RData](state: RState, data: RebelData[RData])
  //  ////                                          (implicit encoder: Encoder[CurrentStateInternal[RState, RData]]): CurrentState = {
  //  ////      CurrentStateInternal[RState, RData](state, data)(encoder)
  //  ////    }
  //  ////  }

  /**
    * Response on EntityCommand
    */
  sealed trait EntityCommandResponse extends RebelResponse
  case class EntityCommandSuccess[S <: Specification](event: S#RDomainEvent) extends EntityCommandResponse
  case class EntityCommandFailed[S <: Specification](event: S#RDomainEvent, rebelErrors: RebelErrors) extends EntityCommandResponse
  case object EntityTooBusy extends EntityCommandResponse

  case class CheckPreconditions[S <: Specification](operation: RebelSyncEvent[S], after: Option[TransactionId] = None)
    extends RebelQuery
  /**
    * @param transactionId id
    * @param result Potential more nested participants, or failure messages
    */
  case class CheckPreconditionsResult(transactionId: TransactionId, result: RebelCheck[Set[TwoPhaseCommit.SyncOperation]]) extends RebelResponse
}
