package com.ing.rebel.benchmark.vs.latency

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, OpenAccount}
import com.ing.rebel.benchmark._
import com.ing.rebel.messages._
import com.ing.rebel._
import com.ing.rebel.{Iban, RebelDomainEvent, RebelError}
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.EUR
import DepositBenchmark._
import com.ing.corebank.rebel.simple_transaction.{Account, Transaction}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import scala.concurrent.duration._

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.Random

/**
  * Run with
  * bench/jmh:run -prof jmh.extras.JFR:dir=./target/ -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.vs.latency.*DepositBenchmarkNoOp.throughput
  * bench/jmh:run -prof jmh.extras.JFR:dir=./target/ -i 10 -wi 10 -f 1 -t 1 com.ing.rebel.benchmark.vs.latency.*
  *
  * 4 parallel
  * [info] PsacDepositBenchmarkNoOp.throughput            thrpt    3  1303,571 ± 4144,628  ops/s
  * [info] StaticPsacDepositBenchmarkNoOp.throughput      thrpt    3  2306,489 ± 5298,109  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput           thrpt    3   841,415 ± 1546,068  ops/s
  *
  * 10 iterations
  * [info] PsacDepositBenchmarkNoOp.throughput            thrpt   10  1856,802 ± 407,924  ops/s
  * [info] StaticPsacDepositBenchmarkNoOp.throughput      thrpt   10  2447,561 ± 533,715  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput           thrpt   10  1077,286 ± 153,116  ops/s
  *
  * on swat:
  * [info] PsacDepositBenchmarkNoOp.throughput            thrpt   10  3629.526 ± 213.720  ops/s
  * [info] StaticPsacDepositBenchmarkNoOp.throughput      thrpt   10  4846.153 ± 203.064  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput           thrpt   10  1693.178 ± 165.403  ops/s
  *
  * 10 parallel
  * [info] Benchmark                                       Mode  Cnt     Score     Error  Units
  * [info] StaticPsacDepositBenchmarkNoOp.throughput      thrpt   10  6468.279 ± 637.875  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput           thrpt   10  1768.849 ±  53.421  ops/s
  * dynamic crashed, 10 > bigger that configured max parallel?
  *
  * new static impl
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt    3  440,974 ±  232,237  ops/s
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt    3   89,522 ±  642,930  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt    3  673,339 ± 1480,616  ops/s
  *
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt    3  480,314 ± 629,941  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt    3  563,022 ± 765,833  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt    3  274,815 ± 466,766  ops/s
  *
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt   10  1238,380 ± 320,821  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt   10  1328,358 ± 253,082  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt   10   716,492 ±  55,341  ops/s
  *
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt   10   696,583 ±  49,704  ops/s
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt   10  1236,436 ± 364,433  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt   10  1464,815 ± 223,127  ops/s
  *
  * on battery, with improved PSAC
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt    3   218,081 ± 2204,849  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt    3   567,023 ± 1064,744  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt    3  1748,928 ± 3638,339  ops/s
  *
  * improved
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt    3   113,995 ± 1109,734  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt    3   548,754 ±  724,230  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt    3  1339,863 ± 6053,330  ops/s
  *
  * swat, improved
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt   30   397.056 ±  37.536  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt   30  1186.934 ±  35.831  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt   30  3340.501 ± 503.371  ops/s
  *
  * p4
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt        810,347          ops/s
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt        881,159          ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt       1107,308          ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt   10   888,592 ±  88,512  ops/s
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt   10  1448,449 ± 224,997  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt   10  1503,246 ± 273,451  ops/s
  *
  * [info] PsacDepositBenchmarkInMem.sample                       sample  35455    0,703 ± 0,041  ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.00          sample           0,235          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.50          sample           0,459          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.90          sample           0,806          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.95          sample           1,036          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.99          sample           2,672          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.999         sample          40,417          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.9999        sample          58,255          ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p1.00          sample          67,371          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample                 sample  34051    0,735 ± 0,056  ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.00    sample           0,220          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.50    sample           0,457          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.90    sample           0,715          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.95    sample           0,908          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.99    sample           3,019          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.999   sample          51,195          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.9999  sample         118,041          ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p1.00    sample         132,514          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample                      sample  23544    1,062 ± 0,058  ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.00         sample           0,543          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.50         sample           0,801          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.90         sample           1,073          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.95         sample           1,212          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.99         sample           2,958          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.999        sample          48,068          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.9999       sample          56,839          ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p1.00         sample          58,393          ms/op
  *
  * p10
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt   10   334,839 ± 146,429  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt   10   760,340 ±  97,135  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt   10  1773,793 ± 419,660  ops/s
  *
  * p10 max8
  * [info] TwoPCDepositBenchmarkInMem.throughput           thrpt   10   575,794 ± 148,800  ops/s
  * [info] PsacDepositBenchmarkInMem.throughput            thrpt   10  1187,187 ± 642,516  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput      thrpt   10  1440,162 ± 589,396  ops/s
  */
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@OperationsPerInvocation(operationsPerInvocation)
abstract class DepositBenchmark extends BenchmarkSetup {
  val iban = Iban(s"NL1")

  @Setup
  def startAccount(): Unit = {
    val openAccount = OpenAccount(EUR(Integer.MAX_VALUE))
    AccountSharding(system).tell(iban, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)

    // Apparently also failure can occur, maybe due to threads running this at the same time
    val success = probe.expectMsgType[EntityCommandSuccess[_]]

    AccountSharding(system).tell(iban, TellState(success.event.transactionId))(probe.ref)
    probe.expectMsg(CurrentState(Account.Opened, Initialised(Account.Data(Some(EUR(Integer.MAX_VALUE))))))

    system.log.info("Finished starting 1 Account")
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def throughput(bh: Blackhole): Seq[Unit] = {
    deposit(bh)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def sample(bh: Blackhole): Seq[Unit] = {
    deposit(bh)
  }

  var count = 0

  def deposit(bh: Blackhole): Seq[Unit] = {
    count += 1
    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
      tryAsk(RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId(s"t-${count.toString}-$i")), s"$count-$i").map(bh.consume)
    }
    Await.result(Future.sequence(result), 60.seconds)
  }

  // keep on repeating until one success is found
  def tryAsk(event: Account.RDomainEvent, infoString: String, retryCount: Int = 0): Future[EntityCommandSuccess[_]] = {
    // use new transaction id to make sure that participant starts fresh participant actors
    val newId = TransactionId(s"t-$infoString-$retryCount")
    if(system.log.isWarningEnabled && retryCount > 0) {
      system.log.warning(s"(Re)Asking transaction {}, count {}", infoString, retryCount)
    }
    AccountSharding(system)
      .ask(iban, RebelCommand(event.copy(transactionId = newId)))(probe.ref, 60.seconds)
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

object DepositBenchmark {
  final val maxTransactionsInProgress = 8
  final val maxTransactionsInProgressString = "8"

  final val operationsPerInvocation = 10
  final val operationsPerInvocationString = "10"
}

class PsacDepositBenchmarkNoOp extends PsacDepositBenchmark with NoOpPersistenceConfig
class TwoPCDepositBenchmarkNoOp extends TwoPCDepositBenchmark with NoOpPersistenceConfig
class StaticPsacDepositBenchmarkNoOp extends StaticPsacDepositBenchmark with NoOpPersistenceConfig

class PsacDepositBenchmarkInMem extends PsacDepositBenchmark with InMemoryPersistenceConfig
class TwoPCDepositBenchmarkInMem extends TwoPCDepositBenchmark with InMemoryPersistenceConfig
class StaticPsacDepositBenchmarkInMem extends StaticPsacDepositBenchmark with InMemoryPersistenceConfig

abstract class PsacDepositBenchmark extends DepositBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = dynamic
//       |akka.loglevel = "DEBUG"
//       |rebel.stash-capacity-mailbox.stash-capacity = 500
     """.stripMargin)
}

abstract class TwoPCDepositBenchmark extends DepositBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.command-decider = locking
     """.stripMargin)
}

abstract class StaticPsacDepositBenchmark extends DepositBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = staticthendynamic
     """.stripMargin)
}