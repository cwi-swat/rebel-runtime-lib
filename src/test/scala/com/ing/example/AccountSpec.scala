package com.ing.example

import akka.actor.{ActorRef, PoisonPill}
import com.ing.example.Account._
import com.ing.rebel._
import com.ing.rebel._
import com.ing.util.{IsolatedCluster, MessageLoggerAutoPilot, RebelMatchers}
import squants.market.EUR
import com.ing.example.Account.AccountState
import com.ing.rebel.messages._
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.util.CirceSupport._
import com.ing.rebel.util.MessageLogger
import io.circe.Encoder
import io.circe.generic.auto._

class AccountSpec
  extends IsolatedCluster with RebelMatchers with MessageLoggerAutoPilot {

  val iban1: Iban = Ibans.testIban1

  behavior of "AccountActor"

  private val open: Account.RDomainEvent = RebelDomainEvent(OpenAccount(iban1, EUR(100)), TransactionId("topen"))
  private val deposit: Account.RDomainEvent = RebelDomainEvent(Deposit(EUR(100)), TransactionId("tdeposit"))

  it should "open and deposit" in {
    system.actorOf(MessageLogger.props("target/account-open.html"))
    addTimer()

    val account: ActorRef = system.actorOf(Account.props, "TestAccount")

    account ! TellState()
    // explicit type with AccountData, otherwise compiler can't figure out RData in encoder implicit argument
    val state = CurrentState(New, Uninitialised: RebelData[AccountData])
    expectMsg(state)

    account ! RebelCommand(open)
    expectCommandSuccess(open)

    account ! TellState(open.transactionId)
    expectMsg(CurrentState(Opened, Initialised(Account.AccountData(Some(EUR(100))))))

    account ! RebelCommand(deposit)
    expectCommandSuccess(deposit)

    account ! TellState(deposit.transactionId)
    expectMsg(CurrentState(Opened, Initialised(Account.AccountData(Some(EUR(200))))))
  }

  it should "respond to `CheckPreconditions(...)`" in {
    val account: ActorRef = system.actorOf(Account.props, "TestAccount")

    account ! CheckPreconditions(open)
    expectMsg(CheckPreconditionsResult(open.transactionId, RebelCheck.success(Set())))
  }

  it should "persist and recover" in {
    system.actorOf(MessageLogger.props("target/account-recover.html"))
    addTimer()

    val account: ActorRef = system.actorOf(Account.props, "TestAccount")

    account ! RebelCommand(open)
    expectCommandSuccess(open)
    account ! RebelCommand(deposit)
    expectCommandSuccess(deposit)

    account ! TellState(deposit.transactionId)
    expectMsg(CurrentState(Opened, Initialised(Account.AccountData(Some(EUR(200))))))

    account ! PoisonPill
    Thread.sleep(2000)

    val recoveredAccount: ActorRef = system.actorOf(Account.props, "TestAccount")

    recoveredAccount ! TellState()
    expectMsg(CurrentState(Opened, Initialised(Account.AccountData(Some(EUR(200))))))
  }


}
