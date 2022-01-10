package com.ing.rebel.benchmark.vs.loca.depositwithdraw

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.Account
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, OpenAccount, Withdraw}
import com.ing.rebel.benchmark._
import com.ing.rebel.benchmark.vs.loca.depositwithdraw.InAndDependentBenchmark._
import com.ing.rebel.messages._
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.{Iban, RebelDomainEvent, RebelError, _}
import com.typesafe.config.{Config, ConfigFactory}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.EUR

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

/**
  * Run with
  * bench/jmh:run -prof jmh.extras.JFR:dir=./target/ -rf text -rff ./target/bench.txt -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.vs.loca.depositwithdraw.*BenchmarkNoOp..*TP.*
  *
  * [info] Benchmark                                               Mode  Cnt     Score      Error  Units
  * [info] LocaPsacDepositBenchmarkNoOp.throughput                thrpt    3  2261,561 ± 1540,073  ops/s
  * [info] LocaDepositBenchmarkNoOp.throughput                    thrpt    3  2265,714 ± 5774,918  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput                    thrpt    3  1711,326 ± 2584,861  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput                   thrpt    3   916,634 ± 1777,725  ops/s
  *
  * [info] PsacDepositBenchmarkNoOp.throughput       thrpt    3  2596,110 ± 5358,365  ops/s
  * [info] LocaDepositBenchmarkNoOp.throughput       thrpt    3  2325,332 ± 3847,575  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput      thrpt    3  1116,277 ± 1425,189  ops/s
  *
  * [info] Benchmark                                  Mode  Cnt     Score      Error  Units
  * [info] LocaDepositBenchmarkNoOp.throughput        thrpt   10  3673,104 ± 1985,296  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput        thrpt   10  3482,698 ±  825,209  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput       thrpt   10  1290,211 ±   95,698  ops/s
  * [info] LocaDepositBenchmarkNoOp.throughput1       thrpt   10  1441,577 ± 252,163  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput1       thrpt   10  1360,991 ± 111,241  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput1      thrpt   10  1021,690 ± 261,051  ops/s
  *
  * [info] PsacBenchmarkNoOp.dependentTP      thrpt    3  3596,182 ± 10976,848  ops/s
  * [info] PsacBenchmarkNoOp.independentTP    thrpt    3  3045,316 ± 10843,716  ops/s
  * [info] LocaBenchmarkNoOp.independentTP    thrpt    3  2699,702 ± 10495,588  ops/s
  * [info] LocaBenchmarkNoOp.independentTP1   thrpt    3  1511,787 ±  1606,100  ops/s
  * [info] PsacBenchmarkNoOp.dependentTP1     thrpt    3  1438,635 ±   742,064  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP1  thrpt    3  1461,984 ±  1484,526  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP   thrpt    3  1420,848 ±  1690,915  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP1    thrpt    3  1386,566 ±   620,735  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP     thrpt    3  1365,893 ±  1103,152  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP1     thrpt    3  1361,137 ±  1284,592  ops/s
  * [info] PsacBenchmarkNoOp.independentTP1   thrpt    3  1295,716 ±  1475,856  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP      thrpt    3  1093,078 ±   648,366  ops/s
  *
  * swat
  * [info] LocaDepositBenchmarkNoOp.throughput        thrpt   10  6123.116 ± 397.584  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput        thrpt   10  5188.539 ± 307.171  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput       thrpt   10  1703.845 ±  89.085  ops/s
  *
  * [info] PsacDepositBenchmarkNoOp.throughput1       thrpt   10  1663.887 ± 55.333  ops/s
  * [info] LocaDepositBenchmarkNoOp.throughput1       thrpt   10  1620.017 ± 66.713  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput1      thrpt   10  1538.601 ± 55.856  ops/s
  *
  * [info] LocaDepositBenchmarkNoOp.throughput        thrpt   15  5925.547 ± 266.925  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput        thrpt   15  5244.652 ± 198.148  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput       thrpt   15  1720.735 ±  49.218  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput1       thrpt   15  1639.552 ±  34.676  ops/s
  * [info] LocaDepositBenchmarkNoOp.throughput1       thrpt   15  1629.498 ±  32.736  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput1      thrpt   15  1517.272 ±  42.316  ops/s
  *
  * high c
  * [info] LocaBenchmarkNoOp.independentTP        thrpt    3  6081.878 ± 1785.647  ops/s L
  * [info] PsacBenchmarkNoOp.independentTP        thrpt    3  4978.040 ± 1236.587  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP       thrpt    3  1673.112 ± 1992.692  ops/s 2
  *
  * [info] PsacBenchmarkNoOp.dependentTP          thrpt    3  4789.422 ± 3636.878  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP         thrpt    3  1655.421 ±  446.031  ops/s 2
  * [info] LocaBenchmarkNoOp.dependentTP          thrpt    3  1498.706 ± 4201.217  ops/s L
  *
  * low c
  * [info] PsacBenchmarkNoOp.dependentTP1         thrpt    3  1558.926 ±  909.002  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP1         thrpt    3  1539.167 ± 1250.928  ops/s L
  * [info] TwoPCBenchmarkNoOp.dependentTP1        thrpt    3  1531.670 ±  407.648  ops/s 2
  *
  * [info] PsacBenchmarkNoOp.independentTP1       thrpt    3  1619.455 ±  224.310  ops/s
  * [info] LocaBenchmarkNoOp.independentTP1       thrpt    3  1575.559 ±  673.953  ops/s L
  * [info] TwoPCBenchmarkNoOp.independentTP1      thrpt    3  1517.305 ± 1008.402  ops/s 2
  *
  * swat 10 load. 8 actions
  * [info] LocaThenPsacBenchmarkNoOp.dependentTP1        thrpt   10  1643.986 ±  47.444  ops/s
  * [info] PsacBenchmarkNoOp.dependentTP1         thrpt   10  1566.439 ±  51.449  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP1        thrpt   10  1510.436 ±  59.977  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP1         thrpt   10  1494.011 ±  51.212  ops/s
  *
  * [info] PsacBenchmarkNoOp.independentTP1       thrpt   10  1632.135 ±  68.847  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.independentTP1      thrpt   10  1618.815 ±  60.486  ops/s
  * [info] LocaBenchmarkNoOp.independentTP1       thrpt   10  1618.571 ±  34.441  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP1      thrpt   10  1562.402 ±  91.270  ops/s
  *
  * [info] PsacBenchmarkNoOp.dependentTP          thrpt   10  4979.105 ± 157.879  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.dependentTP         thrpt   10  4864.978 ± 295.813  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP          thrpt   10  1693.374 ± 107.436  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP         thrpt   10  1654.293 ±  88.198  ops/s
  *
  * [info] LocaThenPsacBenchmarkNoOp.independentTP       thrpt   10  6147.588 ± 287.796  ops/s
  * [info] LocaBenchmarkNoOp.independentTP        thrpt   10  6073.519 ± 308.905  ops/s
  * [info] PsacBenchmarkNoOp.independentTP        thrpt   10  4935.024 ± 242.822  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP       thrpt   10  1646.922 ±  64.462  ops/s
  *
  * 25 parallel transactions
  * [info] LocaThenPsacBenchmarkNoOp.dependentTP         thrpt    3  4626.527 ±  1367.747  ops/s
  * [info] PsacBenchmarkNoOp.dependentTP                 thrpt    3  4507.286 ±  1476.222  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP                thrpt    3  1697.469 ±   218.700  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP                 thrpt    3  1659.041 ±   404.385  ops/s
  *
  * [info] PsacBenchmarkNoOp.dependentTP1                thrpt    3  1585.437 ±   359.871  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP1               thrpt    3  1523.105 ±   709.406  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.dependentTP1        thrpt    3  1518.681 ±  1471.100  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP1                thrpt    3  1518.674 ±  1191.825  ops/s
  *
  * [info] LocaBenchmarkNoOp.independentTP               thrpt    3  6336.489 ± 18606.255  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.independentTP       thrpt    3  6330.992 ± 17256.554  ops/s
  * [info] PsacBenchmarkNoOp.independentTP               thrpt    3  3591.258 ±  6342.323  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP              thrpt    3  1508.057 ±   875.634  ops/s
  *
  * [info] LocaThenPsacBenchmarkNoOp.independentTP1      thrpt    3  1617.113 ±   259.296  ops/s
  * [info] PsacBenchmarkNoOp.independentTP1              thrpt    3  1606.759 ±   489.792  ops/s
  * [info] LocaBenchmarkNoOp.independentTP1              thrpt    3  1587.841 ±  1381.381  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP1             thrpt    3  1502.242 ±    82.469  ops/s
  *
  * 25p 15max
  * [info] TwoPCBenchmarkNoOp.dependentTP                thrpt    3  1634.883 ±   490.568  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP                 thrpt    3  1619.592 ±  1056.507  ops/s
  * [info] PsacBenchmarkNoOp.dependentTP                 thrpt    3   556.644 ±   224.690  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.dependentTP         thrpt    3   511.539 ±    49.865  ops/s
  *
  * [info] PsacBenchmarkNoOp.dependentTP1                thrpt    3  1584.109 ±   269.898  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP1                thrpt    3  1556.321 ±   292.762  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.dependentTP1        thrpt    3  1529.301 ±   557.455  ops/s
  * [info] TwoPCBenchmarkNoOp.dependentTP1               thrpt    3  1494.547 ±   428.265  ops/s
  *
  * [info] LocaBenchmarkNoOp.independentTP               thrpt    3  6529.073 ± 14974.893  ops/s
  * [info] LocaThenPsacBenchmarkNoOp.independentTP       thrpt    3  6339.403 ± 14917.411  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP              thrpt    3  1679.213 ±   485.595  ops/s
  * [info] PsacBenchmarkNoOp.independentTP               thrpt    3   587.409 ±   284.858  ops/s
  *
  * [info] LocaThenPsacBenchmarkNoOp.independentTP1      thrpt    3  1631.108 ±   789.356  ops/s
  * [info] PsacBenchmarkNoOp.independentTP1              thrpt    3  1562.561 ±   442.447  ops/s
  * [info] LocaBenchmarkNoOp.independentTP1              thrpt    3  1553.080 ±  1053.002  ops/s
  * [info] TwoPCBenchmarkNoOp.independentTP1             thrpt    3  1484.183 ±   106.156  ops/s
  *
  *
  * 100 max parallel local
  * [info] Benchmark                              Mode  Cnt     Score      Error  Units
  * [info] LocaBenchmarkNoOp.independentTP8      thrpt    3  2362,526 ± 6847,913  ops/s
  * [info] PsacBenchmarkNoOp.independentTP8      thrpt    3  1982,385 ± 7748,687  ops/s
  *
  * [info] PsacBenchmarkNoOp.dependentTP8        thrpt    3  1657,755 ± 3784,088  ops/s
  * [info] LocaBenchmarkNoOp.dependentTP8        thrpt    3  1118,545 ± 1798,238  ops/s
  */
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
abstract class InAndDependentBenchmark extends BenchmarkSetup {
  val iban: Iban = Iban(s"NL1")

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

  var count = 0

  def independent(bh: Blackhole, operationsPerInvocation: Int): Seq[Unit] = {
    count += 1
    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
      tryAsk(RebelDomainEvent(Deposit(EUR(1)), transactionId = TransactionId(s"t-${count.toString}-$i")), s"$count-$i").map(bh.consume)
    }
    Await.result(Future.sequence(result), 60.seconds)
  }

  def dependent(bh: Blackhole, operationsPerInvocation: Int): Seq[Unit] = {
    count += 1
    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
      tryAsk(RebelDomainEvent(Withdraw(EUR(1)), transactionId = TransactionId(s"t-${count.toString}-$i")), s"$count-$i").map(bh.consume)
    }
    Await.result(Future.sequence(result), 60.seconds)
  }

  // sample
  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(1)
  def dependentSample1(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 1)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @OperationsPerInvocation(1)
  def independentSample1(bh: Blackhole): Seq[Unit] = {
    independent(bh, 1)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(2)
  def independentSample2(bh: Blackhole): Seq[Unit] = {
    independent(bh, 2)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(2)
  def dependentSample2(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 2)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(4)
  def independentSample4(bh: Blackhole): Seq[Unit] = {
    independent(bh, 4)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(4)
  def dependentSample4(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 4)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(6)
  def independentSample6(bh: Blackhole): Seq[Unit] = {
    independent(bh, 6)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(6)
  def dependentSample6(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 6)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(8)
  def independentSample8(bh: Blackhole): Seq[Unit] = {
    independent(bh, 8)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(8)
  def dependentSample8(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 8)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(12)
  def independentSample12(bh: Blackhole): Seq[Unit] = {
    independent(bh, 12)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(12)
  def dependentSample12(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 12)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(16)
  def independentSample16(bh: Blackhole): Seq[Unit] = {
    independent(bh, 16)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(16)
  def dependentSample16(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 16)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(20)
  def independentSample20(bh: Blackhole): Seq[Unit] = {
    independent(bh, 20)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(20)
  def dependentSample20(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 20)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(24)
  def independentSample24(bh: Blackhole): Seq[Unit] = {
    independent(bh, 24)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(24)
  def dependentSample24(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 24)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(28)
  def independentSample28(bh: Blackhole): Seq[Unit] = {
    independent(bh, 28)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(28)
  def dependentSample28(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 28)
  }


  // different loads Throughput
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(1)
  def independentTP1(bh: Blackhole): Seq[Unit] = {
    independent(bh, 1)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(1)
  def dependentTP1(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 1)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(2)
  def independentTP2(bh: Blackhole): Seq[Unit] = {
    independent(bh, 2)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(2)
  def dependentTP2(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 2)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(4)
  def independentTP4(bh: Blackhole): Seq[Unit] = {
    independent(bh, 4)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(4)
  def dependentTP4(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 4)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(6)
  def independentTP6(bh: Blackhole): Seq[Unit] = {
    independent(bh, 6)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(6)
  def dependentTP6(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 6)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(8)
  def independentTP8(bh: Blackhole): Seq[Unit] = {
    independent(bh, 8)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(8)
  def dependentTP8(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 8)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(12)
  def independentTP12(bh: Blackhole): Seq[Unit] = {
    independent(bh, 12)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(12)
  def dependentTP12(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 12)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(16)
  def independentTP16(bh: Blackhole): Seq[Unit] = {
    independent(bh, 16)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(16)
  def dependentTP16(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 16)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(20)
  def independentTP20(bh: Blackhole): Seq[Unit] = {
    independent(bh, 20)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(20)
  def dependentTP20(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 20)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(24)
  def independentTP24(bh: Blackhole): Seq[Unit] = {
    independent(bh, 24)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(24)
  def dependentTP24(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 24)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(28)
  def independentTP28(bh: Blackhole): Seq[Unit] = {
    independent(bh, 28)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  @OperationsPerInvocation(28)
  def dependentTP28(bh: Blackhole): Seq[Unit] = {
    dependent(bh, 28)
  }

  // keep on repeating until one success is found
  def tryAsk(event: Account.RDomainEvent, infoString: String, retryCount: Int = 0): Future[EntityCommandSuccess[_]] = {
    // use new transaction id to make sure that participant starts fresh participant actors
    val newId = TransactionId(s"t-$infoString-$retryCount")
    if (system.log.isWarningEnabled && retryCount > 0) {
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

object InAndDependentBenchmark {
  final val maxTransactionsInProgress = 8

//  final val operationsPerInvocation = 25
}

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
     """.stripMargin)
}

abstract class TwoPCBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.command-decider = locking
     """.stripMargin)
}

abstract class LocaBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = staticthenlocking
     """.stripMargin)
}

abstract class LocaThenPsacBenchmark extends InAndDependentBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.command-decider = staticthendynamic
     """.stripMargin)
}