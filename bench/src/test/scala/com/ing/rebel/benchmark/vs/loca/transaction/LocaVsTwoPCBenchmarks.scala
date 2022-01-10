package com.ing.rebel.benchmark.vs.loca.transaction

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.{Account, Transaction}
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, OpenAccount, Withdraw}
import com.ing.corebank.rebel.simple_transaction.Transaction.Book
import com.ing.rebel.benchmark._
import com.ing.rebel.messages._
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.{Iban, RebelDomainEvent, RebelError, _}
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.EUR

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Random

/**
  * Run with
  * bench/jmh:run -prof jmh.extras.JFR:dir=./target/ -rf text -rff ./target/bench.txt -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.vs.loca.transaction.*BenchmarkNoOp..*TP.*
  * bench/jmh:run  -prof jmh.extras.JFR:dir=./target/ -rf csv -rff ./target/bench.csv -i 20 -wi 10 -f 1 com.ing.rebel.benchmark.vs.loca.transaction.*BenchmarkNoOp..*Sample.*
  */
//noinspection ScalaStyle
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
abstract class InAndDependentBenchmark extends BenchmarkSetup {

  // sample

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(1)
  def independentSample1(bh: Blackhole): Seq[Unit] = {
    book(bh, 1)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(2)
  def independentSample2(bh: Blackhole): Seq[Unit] = {
    book(bh, 2)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(4)
  def independentSample4(bh: Blackhole): Seq[Unit] = {
    book(bh, 4)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(6)
  def independentSample6(bh: Blackhole): Seq[Unit] = {
    book(bh, 6)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(8)
  def independentSample8(bh: Blackhole): Seq[Unit] = {
    book(bh, 8)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(12)
  def independentSample12(bh: Blackhole): Seq[Unit] = {
    book(bh, 12)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(16)
  def independentSample16(bh: Blackhole): Seq[Unit] = {
    book(bh, 16)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(20)
  def independentSample20(bh: Blackhole): Seq[Unit] = {
    book(bh, 20)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(24)
  def independentSample24(bh: Blackhole): Seq[Unit] = {
    book(bh, 24)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(28)
  def independentSample28(bh: Blackhole): Seq[Unit] = {
    book(bh, 28)
  }

  // different loads Throughput
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(1)
  def independentTP1(bh: Blackhole): Seq[Unit] = {
    book(bh, 1)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(2)
  def independentTP2(bh: Blackhole): Seq[Unit] = {
    book(bh, 2)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(4)
  def independentTP4(bh: Blackhole): Seq[Unit] = {
    book(bh, 4)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(6)
  def independentTP6(bh: Blackhole): Seq[Unit] = {
    book(bh, 6)
  }


  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(8)
  def independentTP8(bh: Blackhole): Seq[Unit] = {
    book(bh, 8)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(12)
  def independentTP12(bh: Blackhole): Seq[Unit] = {
    book(bh, 12)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(16)
  def independentTP16(bh: Blackhole): Seq[Unit] = {
    book(bh, 16)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(20)
  def independentTP20(bh: Blackhole): Seq[Unit] = {
    book(bh, 20)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(24)
  def independentTP24(bh: Blackhole): Seq[Unit] = {
    book(bh, 24)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(28)
  def independentTP28(bh: Blackhole): Seq[Unit] = {
    book(bh, 28)
  }

  val iban1 = Iban(s"NL1")
  val iban2 = Iban(s"NL2")

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

  var count = 0

  //  def independent(bh: Blackhole, operationsPerInvocation: Int): Seq[Unit] = {
  //    count += 1
  //    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
  //      tryAsk(RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId(s"t-${count.toString}-$i")), s"$count-$i").map(bh.consume)
  //    }
  //    Await.result(Future.sequence(result), 60.seconds)
  //  }
  //
  //  def dependent(bh: Blackhole, operationsPerInvocation: Int): Seq[Unit] = {
  //    count += 1
  //    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
  //      book(bh)
  //      tryAsk(RebelDomainEvent(Withdraw(EUR(1)), transactionId = TransactionId(s"t-${count.toString}-$i")), s"$count-$i").map(bh.consume)
  //    }
  //    Await.result(Future.sequence(result), 60.seconds)
  //  }

  val bookEvent: Book = Transaction.Book(EUR(1), iban1, iban2)

  def book(bh: Blackhole, operationsPerInvocation: Int): Seq[Unit] = {
    count += 1
    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
      //            system.log.error("Sending batch, message {}", i)
      // repeat until success
      tryAsk(RebelDomainEvent(bookEvent, transactionId = TransactionId(s"t-$count-$i")), s"$count-$i").map(bh.consume)
    }
    Await.result(Future.sequence(result), 60.seconds)
  }

  // keep on repeating until one success is found
  def tryAsk(event: Transaction.RDomainEvent, infoString: String, retryCount: Int = 0): Future[EntityCommandSuccess[_]] = {
    // use new transaction id to make sure that participant starts fresh participant actors
    val newId = TransactionId(s"t-$infoString-$retryCount")
    if (system.log.isWarningEnabled && retryCount > 0) {
      system.log.warning(s"(Re)Asking transaction {}, count {}", infoString, retryCount)
    }
    TransactionSharding(system)
      .ask(Math.abs(Random.nextInt()), RebelCommand(event.copy(transactionId = newId)))(probe.ref, 60.seconds)
      //      .toMap[EntityCommandSuccess[_]]
      .flatMap {
      case EntityTooBusy                =>
        // When does this occur?
        system.log.error(s"EntityTooBusy, trying again {}", infoString)
        tryAsk(event, infoString, retryCount + 1)
      case fail: EntityCommandFailed[_] =>
        // happens when TransactionManager times out
        system.log.error("EntityCommandFailed, trying again {} {}", infoString, fail)
        tryAsk(event, infoString, retryCount + 1)
      //        throw new RuntimeException(s"EntityCommandFailed should not happen $e")
      case done: EntityCommandSuccess[_] =>
        system.log.info("EntityCommandSuccess {}", infoString)
        Future.successful(done)
    }(dispatcher)
  }

  implicit lazy val dispatcher: ExecutionContextExecutor = system.dispatcher
}

object InAndDependentBenchmark {
  final val maxTransactionsInProgress = 8
}

import InAndDependentBenchmark.maxTransactionsInProgress

class PsacBenchmarkNoOp extends PsacBenchmark with NoOpPersistenceConfig
class TwoPCBenchmarkNoOp extends TwoPCBenchmark with NoOpPersistenceConfig
class LocaBenchmarkNoOp extends LocaBenchmark with NoOpPersistenceConfig
class LocaThenPsacBenchmarkNoOp extends LocaThenPsacBenchmark with NoOpPersistenceConfig

class PsacBenchmarkInMem extends PsacBenchmark with InMemoryPersistenceConfig
class TwoPCBenchmarkInMem extends TwoPCBenchmark with InMemoryPersistenceConfig
class LocaBenchmarkInMem extends LocaBenchmark with InMemoryPersistenceConfig
class LocaThenPsacBenchmarkInMem extends LocaThenPsacBenchmark with InMemoryPersistenceConfig

abstract class PsacBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = dynamic
//       |akka.loglevel = "DEBUG"
//       |rebel.stash-capacity-mailbox.stash-capacity = 500
       |rebel.sync.two-pc.lock-mechanism=sequential
     """.stripMargin)
}

abstract class TwoPCBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.command-decider = locking
//       |akka.loglevel = "INFO"
       |rebel.sync.two-pc.lock-mechanism=sequential
     """.stripMargin)
}

abstract class LocaBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = staticthenlocking
       |rebel.sync.two-pc.lock-mechanism=sequential
     """.stripMargin)
}

abstract class LocaThenPsacBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = staticthendynamic
       |rebel.sync.two-pc.lock-mechanism=sequential
     """.stripMargin)
}