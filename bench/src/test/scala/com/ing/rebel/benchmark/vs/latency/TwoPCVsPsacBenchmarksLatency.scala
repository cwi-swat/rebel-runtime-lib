package com.ing.rebel.benchmark.vs.latency

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.Account.OpenAccount
import com.ing.corebank.rebel.simple_transaction.Transaction
import com.ing.corebank.rebel.simple_transaction.Transaction.Book
import com.ing.rebel.benchmark._
import com.ing.rebel.benchmark.vs.latency.OpenAccountBenchmark.ThreadState
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

object OpenAccountBenchmark {
  @State(Scope.Thread)
  class ThreadState {
    //noinspection ScalaStyle
    val prefix: String = Random.alphanumeric.take(5).toString()
  }
}

/**
  * Run with
  * sbt "bench/jmh:run -i 10 -wi 10 -f 1 -t 1 com.ing.rebel.benchmark.vs.latency.*"
  * bench/jmh:run -prof jmh.extras.JFR -i 10 -wi 10 -f 1 -t 1 com.ing.rebel.benchmark.vs.latency.*
  *
  * [info] Benchmark                                                      (maxTransactionsInProgress)  (operationsPerInvocation)    Mode     Cnt     Score     Error  Units
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.throughput                                     100                          6   thrpt      10   389,625 ±  86,139  ops/s
  * [info] InMemoryPsacBookBenchmark.throughput                                                   100                          6   thrpt      10   394,558 ± 121,664  ops/s
  * [info] InMemoryTwoPCBookBenchmark.throughput                                                  100                          6   thrpt      10   290,467 ±  50,516  ops/s
  * [info] NoOpPsacBookBenchmark.throughput                                                       100                          6   thrpt      10   176,228 ± 467,716  ops/s
  * [info] NoOpPsacSeqLocksBookBenchmark.throughput                                               100                          6   thrpt      10  1345,931 ± 240,851  ops/s
  * [info] NoOpTwoPCBookBenchmark.throughput                                                      100                          6   thrpt      10   934,340 ± 123,922  ops/s
  * [info] NoOpTwoPCSeqLocksBookBenchmark.throughput                                              100                          6   thrpt      10   662,370 ±  55,766  ops/s
  * [info] PsacDepositBenchmarkInMem.throughput                                                   N/A                        N/A   thrpt      10  1886,562 ± 292,726  ops/s
  * [info] PsacDepositBenchmarkNoOp.throughput                                                    N/A                        N/A   thrpt      10  3826,325 ± 539,962  ops/s
  * [info] StaticPsacDepositBenchmarkInMem.throughput                                             N/A                        N/A   thrpt      10  2281,309 ± 367,009  ops/s
  * [info] StaticPsacDepositBenchmarkNoOp.throughput                                              N/A                        N/A   thrpt      10  4253,292 ± 878,139  ops/s
  * [info] TwoPCDepositBenchmarkInMem.throughput                                                  N/A                        N/A   thrpt      10   866,785 ± 143,355  ops/s
  * [info] TwoPCDepositBenchmarkNoOp.throughput                                                   N/A                        N/A   thrpt      10  1235,487 ±  97,439  ops/s
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample                                         100                          6  sample   11818     1,419 ±   0,089  ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.00                            100                          6  sample             0,474            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.50                            100                          6  sample             0,971            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.90                            100                          6  sample             1,556            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.95                            100                          6  sample             1,974            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.99                            100                          6  sample            16,935            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.999                           100                          6  sample            38,201            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p0.9999                          100                          6  sample            59,077            ms/op
  * [info] InMemoryHigherTimeoutsPsacBookBenchmark.sample:sample·p1.00                            100                          6  sample            60,686            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount                                               N/A                        N/A  sample   60539     1,651 ±   0,113  ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.00                             N/A                        N/A  sample             0,640            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.50                             N/A                        N/A  sample             1,075            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.90                             N/A                        N/A  sample             1,647            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.95                             N/A                        N/A  sample             1,948            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.99                             N/A                        N/A  sample             2,970            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.999                            N/A                        N/A  sample           172,082            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p0.9999                           N/A                        N/A  sample           243,270            ms/op
  * [info] InMemoryOpenAccountBenchmark.openAccount:openAccount·p1.00                             N/A                        N/A  sample           328,204            ms/op
  * [info] InMemoryPsacBookBenchmark.sample                                                       100                          6  sample   10689     1,559 ±   0,103  ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.00                                          100                          6  sample             0,506            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.50                                          100                          6  sample             0,988            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.90                                          100                          6  sample             1,782            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.95                                          100                          6  sample             2,787            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.99                                          100                          6  sample            19,038            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.999                                         100                          6  sample            42,528            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p0.9999                                        100                          6  sample            60,512            ms/op
  * [info] InMemoryPsacBookBenchmark.sample:sample·p1.00                                          100                          6  sample            60,883            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample                                                      100                          6  sample    7710     2,163 ±   0,140  ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.00                                         100                          6  sample             0,818            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.50                                         100                          6  sample             1,520            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.90                                         100                          6  sample             2,199            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.95                                         100                          6  sample             2,824            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.99                                         100                          6  sample            24,045            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.999                                        100                          6  sample            42,196            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p0.9999                                       100                          6  sample            75,235            ms/op
  * [info] InMemoryTwoPCBookBenchmark.sample:sample·p1.00                                         100                          6  sample            75,235            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount                                                   N/A                        N/A  sample  105894     0,945 ±   0,058  ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.00                                 N/A                        N/A  sample             0,363            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.50                                 N/A                        N/A  sample             0,654            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.90                                 N/A                        N/A  sample             0,866            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.95                                 N/A                        N/A  sample             0,984            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.99                                 N/A                        N/A  sample             1,450            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.999                                N/A                        N/A  sample           111,438            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p0.9999                               N/A                        N/A  sample           204,607            ms/op
  * [info] NoOpOpenAccountBenchmark.openAccount:openAccount·p1.00                                 N/A                        N/A  sample           288,883            ms/op
  * [info] NoOpPsacBookBenchmark.sample                                                           100                          6  sample    7457     2,366 ±   1,092  ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.00                                              100                          6  sample             0,269            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.50                                              100                          6  sample             0,577            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.90                                              100                          6  sample             1,009            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.95                                              100                          6  sample             1,299            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.99                                              100                          6  sample             9,442            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.999                                             100                          6  sample           366,609            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p0.9999                                            100                          6  sample          1296,040            ms/op
  * [info] NoOpPsacBookBenchmark.sample:sample·p1.00                                              100                          6  sample          1296,040            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample                                                   100                          6  sample   23425     0,713 ±   0,042  ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.00                                      100                          6  sample             0,286            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.50                                      100                          6  sample             0,513            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.90                                      100                          6  sample             0,835            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.95                                      100                          6  sample             1,023            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.99                                      100                          6  sample             2,869            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.999                                     100                          6  sample            31,168            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p0.9999                                    100                          6  sample            51,944            ms/op
  * [info] NoOpPsacSeqLocksBookBenchmark.sample:sample·p1.00                                      100                          6  sample           125,043            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample                                                          100                          6  sample   14397     1,159 ±   0,061  ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.00                                             100                          6  sample             0,520            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.50                                             100                          6  sample             0,859            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.90                                             100                          6  sample             1,350            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.95                                             100                          6  sample             1,647            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.99                                             100                          6  sample             7,243            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.999                                            100                          6  sample            33,739            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p0.9999                                           100                          6  sample            62,126            ms/op
  * [info] NoOpTwoPCBookBenchmark.sample:sample·p1.00                                             100                          6  sample            64,029            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample                                                  100                          6  sample   10661     1,563 ±   0,063  ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.00                                     100                          6  sample             0,848            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.50                                     100                          6  sample             1,251            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.90                                     100                          6  sample             1,774            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.95                                     100                          6  sample             2,050            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.99                                     100                          6  sample            11,228            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.999                                    100                          6  sample            27,984            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p0.9999                                   100                          6  sample            41,723            ms/op
  * [info] NoOpTwoPCSeqLocksBookBenchmark.sample:sample·p1.00                                     100                          6  sample            42,074            ms/op
  * [info] PsacDepositBenchmarkInMem.sample                                                       N/A                        N/A  sample   19796     0,505 ±   0,033  ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.00                                          N/A                        N/A  sample             0,205            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.50                                          N/A                        N/A  sample             0,332            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.90                                          N/A                        N/A  sample             0,471            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.95                                          N/A                        N/A  sample             0,565            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.99                                          N/A                        N/A  sample             6,867            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.999                                         N/A                        N/A  sample            19,005            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p0.9999                                        N/A                        N/A  sample            40,195            ms/op
  * [info] PsacDepositBenchmarkInMem.sample:sample·p1.00                                          N/A                        N/A  sample            41,222            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample                                                        N/A                        N/A  sample   32550     0,307 ±   0,018  ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.00                                           N/A                        N/A  sample             0,117            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.50                                           N/A                        N/A  sample             0,211            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.90                                           N/A                        N/A  sample             0,353            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.95                                           N/A                        N/A  sample             0,421            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.99                                           N/A                        N/A  sample             1,066            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.999                                          N/A                        N/A  sample            16,496            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p0.9999                                         N/A                        N/A  sample            28,022            ms/op
  * [info] PsacDepositBenchmarkNoOp.sample:sample·p1.00                                           N/A                        N/A  sample            29,262            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample                                                 N/A                        N/A  sample   17514     0,571 ±   0,037  ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.00                                    N/A                        N/A  sample             0,163            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.50                                    N/A                        N/A  sample             0,306            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.90                                    N/A                        N/A  sample             0,542            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.95                                    N/A                        N/A  sample             1,186            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.99                                    N/A                        N/A  sample             7,951            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.999                                   N/A                        N/A  sample            18,857            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p0.9999                                  N/A                        N/A  sample            25,533            ms/op
  * [info] StaticPsacDepositBenchmarkInMem.sample:sample·p1.00                                    N/A                        N/A  sample            26,051            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample                                                  N/A                        N/A  sample   39801     0,251 ±   0,014  ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.00                                     N/A                        N/A  sample             0,086            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.50                                     N/A                        N/A  sample             0,167            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.90                                     N/A                        N/A  sample             0,288            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.95                                     N/A                        N/A  sample             0,345            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.99                                     N/A                        N/A  sample             0,779            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.999                                    N/A                        N/A  sample            15,211            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p0.9999                                   N/A                        N/A  sample            24,533            ms/op
  * [info] StaticPsacDepositBenchmarkNoOp.sample:sample·p1.00                                     N/A                        N/A  sample            32,080            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample                                                      N/A                        N/A  sample    8362     1,202 ±   0,077  ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.00                                         N/A                        N/A  sample             0,610            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.50                                         N/A                        N/A  sample             0,820            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.90                                         N/A                        N/A  sample             1,110            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.95                                         N/A                        N/A  sample             1,466            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.99                                         N/A                        N/A  sample            14,809            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.999                                        N/A                        N/A  sample            24,907            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p0.9999                                       N/A                        N/A  sample            36,438            ms/op
  * [info] TwoPCDepositBenchmarkInMem.sample:sample·p1.00                                         N/A                        N/A  sample            36,438            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample                                                       N/A                        N/A  sample   13563     0,738 ±   0,035  ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.00                                          N/A                        N/A  sample             0,347            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.50                                          N/A                        N/A  sample             0,579            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.90                                          N/A                        N/A  sample             0,751            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.95                                          N/A                        N/A  sample             0,876            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.99                                          N/A                        N/A  sample             5,782            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.999                                         N/A                        N/A  sample            19,421            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p0.9999                                        N/A                        N/A  sample            29,551            ms/op
  * [info] TwoPCDepositBenchmarkNoOp.sample:sample·p1.00                                          N/A                        N/A  sample            29,983            ms/op
*/
@BenchmarkMode(Array(Mode.SampleTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@OperationsPerInvocation(1)
abstract class OpenAccountBenchmark extends BenchmarkSetup {
  var count = 1

  @Benchmark
  def openAccount(state: ThreadState): EntityCommandSuccess[_] = {
    count += 1
    //    messages.foreach { i =>
    val iban = Iban(s"${state.prefix}-${count.toString}")
    //noinspection ScalaStyle
    val openAccount: OpenAccount = OpenAccount(EUR(100))
    AccountSharding(system).tell(iban, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)
    //    }
    //    receiveAndAssert()
    probe.expectMsgType[EntityCommandSuccess[_]]
  }

}

object TwoPCBookBenchmark {
  final val maxTransactionsInProgress = 100
  final val maxTransactionsInProgressString = "100"

  final val operationsPerInvocation = 6
  final val operationsPerInvocationString = "6"
}

@State(Scope.Benchmark)
@OperationsPerInvocation(TwoPCBookBenchmark.operationsPerInvocation)
abstract class TwoPCBookBenchmark extends BenchmarkSetup {

  val iban1 = Iban(s"NL1")
  val iban2 = Iban(s"NL2")

  @Param(Array(
//    "4", "6", "8",
//    "8"
//    "10", "12",
//    "10000"
    TwoPCBookBenchmark.maxTransactionsInProgressString
  ))
  // needs dummy value otherwise it crashes
  var maxTransactionsInProgress: Int = TwoPCBookBenchmark.maxTransactionsInProgress
  @Param(Array(TwoPCBookBenchmark.operationsPerInvocationString))
  var operationsPerInvocation: Int = TwoPCBookBenchmark.operationsPerInvocation

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
          // happens when TransactionManager times out
          system.log.error("EntityCommandFailed, trying again {}", fail)
          tryAsk(event)
        //        throw new RuntimeException(s"EntityCommandFailed should not happen $e")
        case done: EntityCommandSuccess[_] => done
      }(dispatcher)
  }

  implicit lazy val dispatcher: ExecutionContextExecutor = system.dispatcher

  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def throughput(bh: Blackhole): Seq[Unit] = {
    book(bh)
  }

  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def sample(bh: Blackhole): Seq[Unit] = {
    book(bh)
  }

  def book(bh: Blackhole): Seq[Unit] = {
    val result: Seq[Future[Unit]] = (1 to operationsPerInvocation).map { i =>
//            system.log.error("Sending batch, message {}", i)
      // repeat until success
      tryAsk(RebelDomainEvent(bookEvent)).map(bh.consume)
    }
    Await.result(Future.sequence(result), 60.seconds)
  }
}

abstract class PsacBookBenchmark extends TwoPCBookBenchmark {

  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
     """.stripMargin)
}

abstract class TwoPCSeqLocksBookBenchmark extends TwoPCBookBenchmark {

  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.two-pc.lock-mechanism = sequential
     """.stripMargin)
}

abstract class PsacSeqLocksBookBenchmark extends TwoPCBookBenchmark {

  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.sync.two-pc.lock-mechanism = sequential
     """.stripMargin)
}

abstract class HigherTimeoutsPsacBookBenchmark extends PsacBookBenchmark {
  override val extraConfig: Config = ConfigFactory.parseString(
    s"""rebel.sync.max-transactions-in-progress = $maxTransactionsInProgress
       |rebel.two-pc {
       |      manager-retry-duration = 4 s
       |      manager-timeout = 5 s
       |      participant-timeout = 10 s
       |}
     """.stripMargin)
}

//class NoOpTwoPCVsPsacBenchmark extends TwoPCVsPsacBenchmark with NoOpPersistenceConfig

class InMemoryTwoPCBookBenchmark extends TwoPCBookBenchmark with InMemoryPersistenceConfig
class InMemoryPsacBookBenchmark extends PsacBookBenchmark with InMemoryPersistenceConfig
class InMemoryHigherTimeoutsPsacBookBenchmark extends HigherTimeoutsPsacBookBenchmark with InMemoryPersistenceConfig

class NoOpTwoPCBookBenchmark extends TwoPCBookBenchmark with NoOpPersistenceConfig
class NoOpTwoPCSeqLocksBookBenchmark extends TwoPCSeqLocksBookBenchmark with NoOpPersistenceConfig
class NoOpPsacBookBenchmark extends PsacBookBenchmark with NoOpPersistenceConfig
class NoOpPsacSeqLocksBookBenchmark extends PsacSeqLocksBookBenchmark with NoOpPersistenceConfig
//class NoOpHigherTimeoutsPsacBookBenchmark extends HigherTimeoutsPsacBookBenchmark with NoOpPersistenceConfig

class InMemoryOpenAccountBenchmark extends OpenAccountBenchmark with InMemoryPersistenceConfig
class NoOpOpenAccountBenchmark extends OpenAccountBenchmark with NoOpPersistenceConfig

//class LevelDbTwoPCVsPsacBenchmark extends TwoPCVsPsacBenchmark with LevelDbConfig

//class CassandraTwoPCVsPsacBenchmark extends TwoPCVsPsacBenchmark with CassandraConfig