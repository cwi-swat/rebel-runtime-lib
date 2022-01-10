package com.ing.rebel.consistency

import akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import akka.stream.ActorMaterializer
import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._
import com.ing.rebel._
import com.ing.rebel.consistency.ConsistencyCheck.{ConsistencyError, ConsistencyResult, InternalState}
import com.ing.rebel.specification.{RebelSpecification, Specification}

import scala.concurrent.Future

object ConsistencyCheck {
  case class InternalState[State, Data](state: State, data: RebelData[Data])
  case class ConsistencyError[State, Data](beforeState: InternalState[State, Data],
                                           afterState: InternalState[State, Data],
                                           command: SpecificationEvent,
                                           messages: Set[RebelError])
  case class ConsistencyResult[State, Data](id: String, errors: Seq[ConsistencyError[State, Data]], internalState: InternalState[State, Data])
}

trait ConsistencyCheck[S <: Specification] {
  val readJournal: CurrentEventsByPersistenceIdQuery
  implicit val mat: ActorMaterializer
  val specificationLogic: RebelSpecification[S]

  def checkAccountConsistency(persistenceId: String): Future[ConsistencyResult[S#State, S#Data]] = {

    val initialResult: ConsistencyResult[S#State, S#Data] =
      ConsistencyResult(persistenceId, Seq(), InternalState(specificationLogic.initialState, specificationLogic.initialData))

    readJournal.currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)
      .runFold(initialResult) {
        case (prevResult, event) =>
          //          mat.system.log.info(s"Handling {}", event)
          event.event match {
            case domainEvent: specificationLogic.RDomainEvent => {
              val beforeData: specificationLogic.RData = prevResult.internalState.data
              val beforeState: specificationLogic.RState = prevResult.internalState.state
              val event: specificationLogic.SpecEvent = domainEvent.specEvent
              val preConditionsCheck: RebelConditionCheck = specificationLogic.checkPreConditions(beforeData, domainEvent.timestamp)(event)
              val projectedStateData: specificationLogic.RData = specificationLogic.applyPostConditions(beforeData, domainEvent)
              val invariants: RebelConditionCheck = specificationLogic.invariantsHold(projectedStateData)
              val postConditionsCheck: RebelConditionCheck = specificationLogic.checkPostConditions(beforeData, domainEvent.timestamp, projectedStateData)(event)
              val nextStateCheck: RebelConditionCheck = specificationLogic.nextStateGeneric(beforeState, event).map(_ => ())
              val nextState = specificationLogic.nextState(beforeState, event)

              val nextStateTuple: InternalState[specificationLogic.RState, S#Data] = InternalState(nextState, projectedStateData)
              val validated: RebelConditionCheck =
                nextStateCheck combine preConditionsCheck combine postConditionsCheck combine invariants
              val allErrors: Validated[ConsistencyError[specificationLogic.RState, S#Data], RebelSuccess] = validated.leftMap(errors =>
                ConsistencyError(beforeState = prevResult.internalState, afterState = nextStateTuple, command = event, messages = errors.toList.toSet))
              val consistencyErrors: Seq[ConsistencyError[specificationLogic.RState, S#Data]] = allErrors match {
                case Valid(_)       => Seq()
                case Invalid(error) => Seq(error)
              }
              val newErrors: Seq[ConsistencyError[S#State, S#Data]] = prevResult.errors ++ consistencyErrors
              prevResult.copy(internalState = nextStateTuple, errors = newErrors)
            }
            //            case p: PersistentFsmEvent             => prevResult // do nothing?
            case other =>
              //              system.log.warning(s"Encountered unexpected event {}, continuing", other)
              prevResult
          }

      }
  }
}