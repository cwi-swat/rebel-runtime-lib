package com.ing.example

import akka.actor.Terminated
import com.ing.example.sharding.AccountSharding
import com.ing.rebel.{Ibans, RebelDomainEvent}
import com.ing.rebel.messages.{EntityCommandSuccess, RebelCommand}
import com.ing.rebel.sync.twophasecommit.EntityHost
import com.ing.rebel.util.MessageLogger
import com.ing.util.{IsolatedCluster, MessageLoggerAutoPilot, RebelMatchers}
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import squants.market.EUR

class TransactionSpec extends IsolatedCluster with MessageLoggerAutoPilot with RebelMatchers {

  def openAccounts(): EntityCommandSuccess[_] = {
    val accounts = AccountSharding(system)
    accounts.start()

    val open1: RebelDomainEvent[Account.type ] = RebelDomainEvent(Account.OpenAccount(Ibans.testIban1, EUR(100)))
    val open2 = RebelDomainEvent(Account.OpenAccount(Ibans.testIban2, EUR(100)))
    accounts.tell(Ibans.testIban1, RebelCommand(open1))
    expectCommandSuccess(open1)
    accounts.tell(Ibans.testIban2, RebelCommand(open2))
    expectCommandSuccess(open2)
  }

  it should "run transaction with syncs" in {
    system.actorOf(MessageLogger.props("target/transaction.html"))
    addTimer()

    val transaction = system.actorOf(Transaction.props, "transaction")

    openAccounts()

    val start: Transaction.RDomainEvent = RebelDomainEvent(Transaction.Start(1, EUR(100), Ibans.testIban1, Ibans.testIban2, DateTime.now))
    transaction ! RebelCommand(start)

    expectCommandSuccess(start)
  }

  it should "stop when in final state" in {
    system.actorOf(MessageLogger.props("target/stop-in-final.html"))
    addTimer()

    val transaction = system.actorOf(Transaction.props, "transaction")
    watch(transaction)
    openAccounts()

    val start: Transaction.RDomainEvent = RebelDomainEvent(Transaction.Start(1, EUR(100), Ibans.testIban1, Ibans.testIban2, DateTime.now))
    transaction ! RebelCommand(start)
    expectCommandSuccess(start)

    val book: Transaction.RDomainEvent = RebelDomainEvent(Transaction.Book)
    transaction ! RebelCommand(book)
    expectCommandSuccess(book)

    expectTerminated(transaction)


    Thread.sleep(2000)
  }

}
