package com.ing.example

import com.ing.example.ExternalAgreement.AnyState
import com.ing.example.sharding.ExternalAgreementSharding
import com.ing.rebel.{InState, Initialised, IsInitialized, RebelCheck, RebelConditionCheck, RebelDomainEvent, RebelState, RebelSyncEvent, SpecificationEvent}
import com.ing.rebel.messages._
import com.ing.rebel.specification.StateLink
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.util.MessageLogger
import com.ing.util.{IsolatedCluster, MessageLoggerAutoPilot, RebelMatchers}
import io.circe.generic.auto._
import com.ing.rebel.util.CirceSupport._

class ExternalAgreementActorTest extends IsolatedCluster with MessageLoggerAutoPilot with RebelMatchers {

//  case object SomeCommand extends SpecificationEvent
//  val syncEvent: RebelDomainEvent[SpecificationEvent] = RebelDomainEvent(SomeCommand)
//  val tId: TransactionId = TransactionId("tid")

//  case object SomeState extends RebelState

  it should "respond to the same messages as an RebelFSMActor" in {
    system.actorOf(MessageLogger.props("target/external-actor.html"))
    addTimer()

    ExternalAgreementSharding(system).start()

    val tId: TransactionId = TransactionId("tid")
    val syncEvent = RebelDomainEvent(ExternalAgreement.BookingAllowed, tId)

    ExternalAgreementSharding(system) ! ("E1", RebelCommand(syncEvent))
    expectCommandSuccess(syncEvent)

    ExternalAgreementSharding(system) ! ("E1", CheckPreconditions(syncEvent))
    expectMsg(CheckPreconditionsResult(tId, RebelCheck.success(Set())))

    ExternalAgreementSharding(system) ! ("E1", CheckPreconditions(InState[ExternalAgreement.type](tId, AnyState)))
    expectMsg(CheckPreconditionsResult(tId, RebelCheck.success(Set())))

    ExternalAgreementSharding(system) ! ("E1", CheckPreconditions(IsInitialized(tId)))
    expectMsg(CheckPreconditionsResult(tId, RebelCheck.success(Set())))

    ExternalAgreementSharding(system) ! ("E1", CheckPreconditions(syncEvent))
    expectMsg(CheckPreconditionsResult(tId, RebelCheck.success(Set())))

    implicit val x: StateLink[ExternalAgreement.State, ExternalAgreement.type] =  ExternalAgreement.stateLink
    ExternalAgreementSharding(system) ! ("E1", TellState())
    expectMsg(CurrentState(ExternalAgreement.defaultState, Initialised(ExternalAgreement.defaultData)))
  }

}
