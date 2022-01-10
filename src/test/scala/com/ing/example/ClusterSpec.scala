package com.ing.example

import akka.actor.{ActorRef, PoisonPill}
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.pattern.ask
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKitBase}
import cats.scalatest.{ValidatedMatchers, ValidatedValues}
import com.ing.example.Account._
import com.ing.example.Transaction.Start
import com.ing.example.sharding.{AccountSharding, ExternalAgreementSharding, TransactionSharding}
import com.ing.rebel.RebelError.SyncFailed
import com.ing.rebel._
import com.ing.rebel.messages._
import com.ing.rebel.sync.RebelSync
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.util.MessageLogger
import com.ing.util._
import com.typesafe.config.ConfigFactory
//import kamon.Kamon
//import kamon.trace.Tracer
import org.joda.time.DateTime
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import squants.market.EUR

import scala.concurrent.duration._
import io.circe.generic.auto._
import com.ing.rebel.util.CirceSupport._

class InMemClusterSpec extends IsolatedCluster(ConfigFactory.parseString(
  """rebel.sync.max-transactions-in-progress = 1
    |rebel.sync.command-decider = locking
  """.stripMargin))
  with ClusterSpec

class InMemSeqLocksClusterSpec extends IsolatedCluster(ConfigFactory.parseString(
  """
    |rebel.sync.max-transactions-in-progress = 1
    |rebel.sync.two-pc.lock-mechanism = sequential
    |rebel.sync.command-decider = locking
    """.stripMargin))
  with ClusterSpec

class InMemClusterPsacSpec extends IsolatedCluster(ConfigFactory.parseString(
  """rebel.sync.max-transactions-in-progress = 8
    |rebel.sync.command-decider = dynamic
  """.stripMargin))
  with ClusterSpec

class InMemSeqLocksPsacSpec extends IsolatedCluster(ConfigFactory.parseString(
  """
    |rebel.sync.max-transactions-in-progress = 8
    |rebel.sync.two-pc.lock-mechanism = sequential
    |rebel.sync.command-decider = dynamic
  """.stripMargin))
  with ClusterSpec

class InMemClusterLocaSpec extends IsolatedCluster(ConfigFactory.parseString(
  """rebel.sync.max-transactions-in-progress = 8
    |rebel.sync.command-decider = staticthendynamic
  """.stripMargin))
  with ClusterSpec

class InMemSeqLocksLocaSpec extends IsolatedCluster(ConfigFactory.parseString(
  """
    |rebel.sync.max-transactions-in-progress = 8
    |rebel.sync.two-pc.lock-mechanism = sequential
    |rebel.sync.command-decider = staticthendynamic
  """.stripMargin))
  with ClusterSpec

class CassandraClusterSpec extends CassandraClusterTestKit with CassandraLifecycle with ClusterSpec {
  override val systemName: String = "CassandraClusterSpec"
}

trait ClusterSpec extends ScalaFutures with Matchers with DefaultTimeout with MessageLoggerAutoPilot
  with RebelMatchers with ValidatedMatchers with ValidatedValues {
  this: FlatSpecLike with TestKitBase with ImplicitSender =>

  val iban1: Iban = Ibans.testIban1
  val iban2: Iban = Ibans.testIban2
  val tId: TransactionId = TransactionId("tid")
  val accounts = AccountSharding(system)
  val transactions = TransactionSharding(system)
  val externalAgreements = ExternalAgreementSharding(system)
  accounts.start()
  transactions.start()
  externalAgreements.start()

  "Sharded transaction" should "respond to `CheckPreconditions(...)`" in {
    transactions ! (1, CheckPreconditions(RebelDomainEvent(Transaction.Start(1, EUR(1), iban1, iban2, DateTime.now()),tId)))
    // longer timeout, because first generation of UUID takes longer apparently
    val result = expectMsgType[CheckPreconditionsResult](20.seconds)

    result.transactionId shouldBe tId
    result.result shouldBe valid
    result.result.value should have size 2
  }

  "Sharded account" should "respond to `CheckPreconditions(...)`" in {
    accounts ! (iban1, CheckPreconditions(RebelDomainEvent(Account.OpenAccount(iban1, EUR(100)), tId)))
    expectMsg(CheckPreconditionsResult(tId, RebelCheck.success(Set())))
  }

  "Full lock cycle" should "correctly terminate and unlock on Query" in {
    // Can't easily test without creating extra event to trigger transaction mechanism
    // is indirectly tested in test case below
    open2Accounts(accounts)

    // triggers Query IsInitialized on Account 1/2
    val startCommand: Transaction.RDomainEvent = RebelDomainEvent(Transaction.Start(1, EUR(1), iban1, iban2, DateTime.now))
    transactions ! (1, RebelCommand(startCommand))
    expectCommandSuccess(100.millis, startCommand)

    accounts ! (iban1, TellState(startCommand.transactionId))
    expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(100))))))
  }

  it should "correctly terminate and unlock on command" in {
    // start debug trace
    system.actorOf(MessageLogger.props("target/fullcycle.html"))
    addTimer()

    open2Accounts(accounts)

    val startCommand: Transaction.RDomainEvent = RebelDomainEvent(Transaction.Start(1, EUR(1), iban1, iban2, DateTime.now))
    // TODO inference for RebelCommand? using Link
    transactions ! (1, RebelCommand(startCommand))
    expectCommandSuccess(200.millis, startCommand)
    val book = RebelDomainEvent(Transaction.Book, transactionId = TransactionId("book"))
    transactions ! (1, RebelCommand(book))
    expectCommandSuccess(1000.millis, book)

    accounts ! (iban1, TellState(book.transactionId))
    expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(99))))))

    accounts ! (iban2, TellState(book.transactionId))
    expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(101))))))
  }

  it should "also correctly fail transactions" in {
    system.actorOf(MessageLogger.props("target/fullcycle-failed.html"))
    addTimer()

    val startCommand: Start = Transaction.Start(1, EUR(1), iban1, iban2, DateTime.now)
    transactions ! (1, RebelCommand(RebelDomainEvent(startCommand)))
    val failed = expectMsgType[EntityCommandFailed[Transaction.type]]
    failed.event.specEvent shouldBe a[Transaction.Start]
    atLeast(1, failed.rebelErrors.toNonEmptyList.toList) should matchPattern { case SyncFailed(msg) if msg.contains("IsInitialized") => }

    transactions ! (1, RebelCommand(RebelDomainEvent(Transaction.Book)))
    val bookFailed = expectMsgType[EntityCommandFailed[Transaction.type]]
    bookFailed.event.specEvent shouldBe a[Transaction.Book.type]
    atLeast(1, bookFailed.rebelErrors.toNonEmptyList.toList) should matchPattern { case e: RebelError if e.toString.contains("Command Book not allowed when in state Init") => }
  }

  it should "also correctly fail transactions and stay available" in {
//    system.actorOf(MessageLogger.props("target/fullcycle-failed2.html"))
//    addTimer()

    val openAccount1 = RebelDomainEvent(Account.OpenAccount(iban1, EUR(100)), transactionId = TransactionId("open1"))
    accounts ! (iban1, RebelCommand(openAccount1))
    // allow some slack to let the sharding startup
    expectCommandSuccess(openAccount1)

    val openAccount2 = RebelDomainEvent(Account.OpenAccount(iban1, EUR(100)), transactionId = TransactionId("open2"))
    accounts ! (iban1, RebelCommand(openAccount2))
    // This should happen really fast, otherwise the protocol is blocking somewhere
    val failed = expectMsgType[EntityCommandFailed[Account.type]]
    failed.event.specEvent shouldBe a[Account.OpenAccount]

    val openAccount3 = RebelDomainEvent(Account.OpenAccount(iban1, EUR(100)), transactionId = TransactionId("open3"))
    accounts ! (iban1, RebelCommand(openAccount3))
    val failed2 = expectMsgType[EntityCommandFailed[Account.type]]
    failed2.event.specEvent shouldBe a[Account.OpenAccount]
  }

  def open2Accounts(accounts: AccountSharding): Unit = {
    val openAccount1 = RebelDomainEvent(Account.OpenAccount(iban1, EUR(100)), transactionId = TransactionId("open1"))
    accounts ! (iban1, RebelCommand(openAccount1))
    // allow some slack to let the sharding startup
    expectCommandSuccess(15.seconds,openAccount1)
    val openAccount2 = RebelDomainEvent(Account.OpenAccount(iban2, EUR(100)), transactionId = TransactionId("open2"))
    accounts ! (iban2, RebelCommand(openAccount2))
    // This should happen really fast, otherwise the protocol is blocking somewhere
    expectCommandSuccess(100.millis, openAccount2)

    accounts ! (iban1, TellState(openAccount1.transactionId))
    expectMsg(CurrentState(Account.Opened: Account.AccountState, Initialised(Account.AccountData(Some(EUR(100))))))

    accounts ! (iban2, TellState(openAccount2.transactionId))
    expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(100))))))
  }

  // TODO this tests fails because getting the locks race with each other
  // This is probably expected, a retry would solve this problem.
  // TODO race should be detected and fail fast, such that there is enough time to retry.
  // Simple default ordering of participants does not work, because locks are requested in parallel. Alternative could be to make this sequential,
  // but then it would no longer be 2PC. 2PC works with timeouts and retries
  // Vector clocks? to make sure who is first, this would break 2pc guarantees sometimes. Can only be safe before voting. => vote no to any
  "Locking mechanism" should "correctly handle multiple transactions" in {
//    Kamon.start()
    // start debug trace
    system.actorOf(MessageLogger.props(s"target/multiple-transactions.html"))
    addTimer()

    open2Accounts(accounts)

    val nrOfTransactions = 5

    val transactionNrs = 1 to nrOfTransactions
    var expected: Seq[EntityCommandSuccess[_]] = Seq()
    transactionNrs foreach {
      i =>
        val start: Transaction.RDomainEvent =
          RebelDomainEvent(Transaction.Start(i, EUR(1), iban1, iban2, DateTime.now), transactionId = TransactionId(s"start$i"))
        transactions ! (i, RebelCommand(start))
        val book: Transaction.RDomainEvent = RebelDomainEvent(Transaction.Book, transactionId = TransactionId(s"book$i"))
        transactions ! (i, RebelCommand(book))
        expected +:= EntityCommandSuccess(start)
        expected +:= EntityCommandSuccess(book)
    }

    @scala.annotation.tailrec
    def expectNow(expected: Seq[Any]): Boolean = {
      if (expected.isEmpty) {
        true
      } else {
        system.log.info("Expecting: {}", expected)
        val msg: Any = expectMsgAnyOf(expected: _*)
        // weird diff to make sure not all duplicate values are removed
        expectNow(expected.diff(Seq(msg)))
      }
    }

    //    val received = expectMsgAllOf(expected: _*)
    expectNow(expected) shouldBe true
    //    system.log.info("Received: {}", received)
//    system.log.info(Tracer.Default.toString)
    //    received should have size (nrOfTransactions * 2)
    expectNoMessage()

    // TODO has to do with locks/deadlocks

    //    1 to (nrOfTransactions * 2) foreach { n =>
    //      expectMsgPF() {
    //        case CommandSuccess(s: TransactionActor.Start) if transactionNrs contains s.id => true
    //        case CommandSuccess(TransactionActor.Book)                                     => true
    //        case  _                                                                        => false
    //      }
    //      expectMsgType[CommandSuc  cess]
    //      println(s"Handled $n th CommandSuccess")
    //    }

    val lastTransactionId = expected.last.event.transactionId

    accounts ! (iban1, TellState(lastTransactionId))
    expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(100 - nrOfTransactions))))))
    // trick to eventually observe new state within timeout
    //    fishForMessage(timeout.duration, "") {
    //      case CurrentState(SpecificationState(AccountActor.Opened), Initialised(AccountActor.AccountData(Some(amount), Some(EUR))))
    //        if amount == EUR(100 - nrOfTransactions) => true
    //      case _                                     =>
    //        accounts ! (iban1, TellState)
    //        false
    //    }

    accounts ! (iban2, TellState(lastTransactionId))
    expectMsg(CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(100 + nrOfTransactions))))))
    //    Kamon.shutdown()
  }

  "Real datastores" should "persist and recover" in {
    val account: ActorRef = system.actorOf(Account.props, "TestAccount")

    val open1 = RebelDomainEvent(OpenAccount(iban1, EUR(100)))
    account ! RebelCommand(open1)
    expectCommandSuccess(open1)
    val deposit = RebelDomainEvent(Deposit(EUR(100)))
    account ! RebelCommand(deposit)
    expectCommandSuccess(deposit)

    account ! TellState(deposit.transactionId)
    expectMsg(CurrentState(Opened, Initialised(Account.AccountData(Some(EUR(200))))))

    account ! PoisonPill
    expectNoMessage(1.second)

    val recoveredAccount: ActorRef = system.actorOf(Account.props, "TestAccount")

    recoveredAccount ! TellState()
    expectMsg(CurrentState(Opened, Initialised(Account.AccountData(Some(EUR(200))))))
  }

  "Rebel Actors" should "be passivated after timeout and recover" in {
    system.actorOf(MessageLogger.props("target/timeout-recover.html"))
    addTimer()

    val testShard = TestShardingPassivation(system)
    testShard.start()

    val open = RebelDomainEvent(OpenAccount(iban1, EUR(100)))
    testShard ! (iban1, RebelCommand(open))
    expectCommandSuccess(open)

    def entityIds(): Set[EntityId] = (ClusterSharding(system).shardRegion(testShard.name) ? ShardRegion.GetShardRegionState)
      .mapTo[ShardRegion.CurrentShardRegionState].futureValue.shards.flatMap(_.entityIds)

    entityIds() should contain(iban1.iban)

    // should passivate
    expectNoMessage(testShard.passivationTimeOut + 100.millis)

    entityIds() should not contain iban1.iban

    // should recover
    val deposit = RebelDomainEvent(Deposit(EUR(100)))
    testShard ! (iban1, RebelCommand(deposit))
    expectCommandSuccess(deposit)
  }
}
