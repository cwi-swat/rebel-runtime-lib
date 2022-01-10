package com.ing.corebank.rebel.simple_transaction.actor

import com.ing.corebank.rebel.sharding.{AccountSharding, TransactionSharding}
import com.ing.corebank.rebel.simple_transaction.Account
import com.ing.corebank.rebel.simple_transaction.Account._
import com.ing.corebank.rebel.simple_transaction.Transaction._
import com.ing.rebel.Ibans._
import com.ing.rebel.messages.{CurrentState, EntityCommandSuccess, RebelCommand, TellState}
import com.ing.rebel.{Iban, Ibans, Initialised, RebelDomainEvent}
import com.ing.util.{CassandraClusterTestKit, CassandraLifecycle, RebelMatchers}
import squants.market.EUR
import io.circe.generic.auto._
import com.ing.rebel.util.CirceSupport._

/**
  * Setup for cluster IT tests
  * TODO: sane tests
  */
//noinspection ScalaStyle
class ITClusterSpec extends CassandraClusterTestKit with CassandraLifecycle with RebelMatchers {
  override val systemName: String = "CassandraITClusterSpec"

  val accounts: AccountSharding = AccountSharding(system)
  val transactions: TransactionSharding = TransactionSharding(system)
  accounts.start()
  transactions.start()

  "Transaction" should "correctly book with 2PC" in {
    open2Accounts()

    val book = RebelDomainEvent(Book(EUR(100), testIban1, testIban2))
    transactions.tell(1, RebelCommand(book))
    expectCommandSuccess(book)
  }

  private def open2Accounts() = {
    val openAccount1 = RebelDomainEvent(OpenAccount(EUR(100)))
    accounts ! (testIban1, RebelCommand(openAccount1))
    expectCommandSuccess(openAccount1)
    val openAccount2 = RebelDomainEvent(OpenAccount(EUR(100)))
    accounts ! (testIban2, RebelCommand(openAccount2))
    expectCommandSuccess(openAccount2)

    accounts ! (testIban1, TellState())
    expectMsg(CurrentState(Opened, Initialised(Account.Data(Some(EUR(100))))))

    accounts ! (testIban2, TellState())
    expectMsg(CurrentState(Opened, Initialised(Account.Data(Some(EUR(100))))))
  }
}
