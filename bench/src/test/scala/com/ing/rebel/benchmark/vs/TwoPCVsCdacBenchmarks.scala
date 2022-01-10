package com.ing.rebel.benchmark.vs

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.Account.OpenAccount
import com.ing.corebank.rebel.simple_transaction.Transaction
import com.ing.corebank.rebel.simple_transaction.Transaction.Book
import com.ing.rebel.benchmark._
import com.ing.rebel.messages._
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.{Iban, RebelDomainEvent, RebelError}
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.EUR

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Random

/**
  * Run with
  * sbt "bench/jmh:run -i 10 -wi 10 -f 1 -t 6 com.ing.rebel.benchmark.Vs.*"
  */
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@OperationsPerInvocation(1)
abstract class OpenAccountBenchmark extends BenchmarkSetup {

  var count = 1


  @Benchmark
  def openAccount(): EntityCommandSuccess[_] = {
    count += 1
    //    messages.foreach { i =>
    val iban = Iban(s"${count.toString}")
    //noinspection ScalaStyle
    val openAccount: OpenAccount = OpenAccount(EUR(100))
    AccountSharding(system).tell(iban, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)
    //    }
    //    receiveAndAssert()
    probe.expectMsgType[EntityCommandSuccess[_]]
  }

}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
//@OperationsPerInvocation(TwoPCBookBenchmark.operationsPerInvocation)
abstract class TwoPCBookBenchmark extends BenchmarkSetup {

  val iban1 = Iban(s"NL1")
  val iban2 = Iban(s"NL2")

  @Param(Array(
//    "4", "6", "8",
    "8"
//    "10", "12"
  ))
  // needs dummy value otherwise it crashes
  var maxTransactionsInProgress: Int = 4
  @Param(Array("1"))
  var operationsPerInvocation = 1

  @Setup
  def startAccounts(): Unit = {
    val openAccount = OpenAccount(EUR(Integer.MAX_VALUE))
    AccountSharding(system).tell(iban1, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)
    AccountSharding(system).tell(iban2, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)

    // Apparently also failure can occur, maybe due to threads running this at the same time
    probe.expectMsgType[EntityCommandResponse]
    probe.expectMsgType[EntityCommandResponse]

    system.log.info("Finished starting 2 Accounts")
  }

  val bookEvent: Book = Transaction.Book(EUR(1), iban1, iban2)

  // keep on repeating until one success is found
  def tryAsk(event: Transaction.RDomainEvent): Future[Any] = {
    // use new transaction id to make sure that participant starts fresh participant actors
    val newId = TransactionId(UniqueId("t"))
    TransactionSharding(system)
      .ask(Math.abs(Random.nextInt()), RebelCommand(event.copy(transactionId = newId)))(probe.ref, 60.seconds)
      .map {
        case EntityTooBusy             =>
          system.log.error("EntityTooBusy, trying again")
          tryAsk(event)
        case fail: EntityCommandFailed[_] =>
          system.log.error("EntityCommandFailed, trying again {}", fail)
          tryAsk(event)
        //        throw new RuntimeException(s"EntityCommandFailed should not happen $e")
        case done: EntityCommandSuccess[_] => done
      }(dispatcher)
  }

  implicit lazy val dispatcher: ExecutionContextExecutor = system.dispatcher

  @Benchmark
//  @OperationsPerInvocation(TwoPCBookBenchmark.operationsPerInvocation)
  def book(bh: Blackhole): Seq[Unit] = {
    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
      //      system.log.error("Sending batch {}, message {}", count, i)
      // repeat until success
      tryAsk(RebelDomainEvent(bookEvent)).map(bh.consume)
    }
    Await.result(Future.sequence(result), 60.seconds)
  }
}

abstract class CdacBookBenchmark extends TwoPCBookBenchmark {

  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
//       |rebel.two-pc {
//       |      participant-retry-duration = 500ms
//       |      manager-retry-duration = 500ms
//       |      manager-timeout = 1 s
//       |      participant-timeout = 10 s
//       |}
     """.stripMargin)
}

abstract class HigherTimeoutsCdacBookBenchmark extends CdacBookBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.two-pc {
       |      manager-retry-duration = 4 s
       |      manager-timeout = 5 s
       |      participant-timeout = 10 s
       |}
     """.stripMargin)
}

//class NoOpTwoPCVsCdacBenchmark extends TwoPCVsCdacBenchmark with NoOpPersistenceConfig

class InMemoryTwoPCBookBenchmark extends TwoPCBookBenchmark with InMemoryPersistenceConfig
class InMemoryCdacBookBenchmark extends CdacBookBenchmark with InMemoryPersistenceConfig
class InMemoryHigherTimeoutsCdacBookBenchmark extends HigherTimeoutsCdacBookBenchmark with InMemoryPersistenceConfig

class NoOpTwoPCBookBenchmark extends TwoPCBookBenchmark with NoOpPersistenceConfig
class NoOpCdacBookBenchmark extends CdacBookBenchmark with NoOpPersistenceConfig
class NoOpHigherTimeoutsCdacBookBenchmark extends HigherTimeoutsCdacBookBenchmark with NoOpPersistenceConfig

class InMemoryOpenAccountBenchmark extends OpenAccountBenchmark with InMemoryPersistenceConfig
class NoOpOpenAccountBenchmark extends OpenAccountBenchmark with NoOpPersistenceConfig

//class LevelDbTwoPCVsCdacBenchmark extends TwoPCVsCdacBenchmark with LevelDbConfig

//class CassandraTwoPCVsCdacBenchmark extends TwoPCVsCdacBenchmark with CassandraConfig