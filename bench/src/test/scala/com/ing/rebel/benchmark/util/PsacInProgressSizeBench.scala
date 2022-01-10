package com.ing.rebel.benchmark.util

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.simple_transaction.Account
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, Opened}
import com.ing.rebel.sync.pathsensitive.{DynamicPsacCommandDecider, StaticCommandDecider, TwoPLCommandDecider}
import com.ing.rebel.{Iban, Initialised, RebelDomainEvent, RebelError}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.EUR

/**
  * bench/jmh:run  -prof jmh.extras.JFR:dir=./target/  -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.util.PsacInProgressSizeBench
[info] PsacInProgressSizeBench.dynamic                     1  thrpt    3  366204,521 ± 154123,344  ops/s
[info] PsacInProgressSizeBench.dynamic                     2  thrpt    3  148871,623 ± 120965,146  ops/s
[info] PsacInProgressSizeBench.dynamic                     3  thrpt    3   59089,414 ±  50730,528  ops/s
[info] PsacInProgressSizeBench.dynamic                     4  thrpt    3   24533,316 ±  13990,406  ops/s
[info] PsacInProgressSizeBench.dynamic                     5  thrpt    3   11843,666 ±  13120,891  ops/s
[info] PsacInProgressSizeBench.dynamic                     6  thrpt    3    4557,535 ±   7350,261  ops/s
[info] PsacInProgressSizeBench.dynamic                     7  thrpt    3    2037,149 ±   2981,909  ops/s
[info] PsacInProgressSizeBench.dynamic                     8  thrpt    3  281491,343 ± 993163,018  ops/s
[info] PsacInProgressSizeBench.dynamic:JFR                 8  thrpt              NaN                 N/A
  */

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class PsacInProgressSizeBench {

  @Param(Array("1","2","3","4","5","6","7","8"))
  var inProgressSize: Int = 1

  val dynamicDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, Account.Logic)

  def dynamic(bh: Blackhole): Unit = {
    val inProgress = (1 to inProgressSize).map(i => RebelDomainEvent(Deposit(EUR(1))))
    bh.consume(dynamicDecider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(100)))),
      inProgress, RebelDomainEvent(Deposit(EUR(1)))))
  }

  /**
    * old impl
    * [info] Benchmark                            (inProgressSize)   Mode  Cnt       Score        Error  Units
    * [info] PsacInProgressSizeBench.dynamic                     1  thrpt    3  274591,273 ± 452583,350  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     2  thrpt    3   90350,238 ± 367051,848  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     3  thrpt    3   19933,032 ± 311584,612  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     4  thrpt    3   26029,948 ±  17952,922  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     5  thrpt    3   10923,004 ±   8773,276  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     6  thrpt    3    3297,965 ±  25012,638  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     7  thrpt    3    2117,453 ±    995,512  ops/s
    * [info] PsacInProgressSizeBench.dynamic                     8  thrpt    3  259836,146 ± 476274,026  ops/s
    *
    * swat
    * [info] PsacInProgressSizeBench.throughput                     1  thrpt    3  504679.360 ± 45714.577  ops/s
    * [info] PsacInProgressSizeBench.throughput                     2  thrpt    3  209571.190 ± 20186.191  ops/s
    * [info] PsacInProgressSizeBench.throughput                     3  thrpt    3   87821.335 ± 13733.273  ops/s
    * [info] PsacInProgressSizeBench.throughput                     4  thrpt    3   39560.501 ±  7794.865  ops/s
    * [info] PsacInProgressSizeBench.throughput                     5  thrpt    3   17109.171 ±  3630.567  ops/s
    * [info] PsacInProgressSizeBench.throughput                     6  thrpt    3    7653.221 ±  2010.974  ops/s
    * [info] PsacInProgressSizeBench.throughput                     7  thrpt    3    3245.682 ±  1193.704  ops/s
    * [info] PsacInProgressSizeBench.throughput                     8  thrpt    3  473177.898 ± 31369.293  ops/s
    *
    * new impl (should have less overhead)
    * [info] PsacInProgressSizeBench.throughput                     1  thrpt    3  563807,058 ± 989597,141  ops/s
    * [info] PsacInProgressSizeBench.throughput                     2  thrpt    3  345231,198 ± 268809,990  ops/s
    * [info] PsacInProgressSizeBench.throughput                     3  thrpt    3  178639,845 ± 114700,375  ops/s
    * [info] PsacInProgressSizeBench.throughput                     4  thrpt    3  103524,257 ±  67019,274  ops/s
    * [info] PsacInProgressSizeBench.throughput                     5  thrpt    3   61197,026 ± 172998,077  ops/s
    * [info] PsacInProgressSizeBench.throughput                     6  thrpt    3   33578,492 ±  17262,928  ops/s
    * [info] PsacInProgressSizeBench.throughput                     7  thrpt    3   12676,332 ±  84229,904  ops/s
    * [info] PsacInProgressSizeBench.throughput                     8  thrpt    3  273543,119 ± 378342,842  ops/s
    *
    * zonder Money-optimalisatie
    * [info] Benchmark                               (inProgressSize)   Mode  Cnt       Score         Error  Units
    * [info] PsacInProgressSizeBench.throughput                     1  thrpt    3  541690,035 ± 1007332,399  ops/s
    * [info] PsacInProgressSizeBench.throughput                     2  thrpt    3  246426,159 ±  543795,891  ops/s
    * [info] PsacInProgressSizeBench.throughput                     3  thrpt    3  133864,293 ±  484283,318  ops/s
    * [info] PsacInProgressSizeBench.throughput                     4  thrpt    3   97657,748 ±   11808,607  ops/s
    * [info] PsacInProgressSizeBench.throughput                     5  thrpt    3   50584,334 ±   57701,794  ops/s
    * [info] PsacInProgressSizeBench.throughput                     6  thrpt    3   26333,878 ±   59973,010  ops/s
    * [info] PsacInProgressSizeBench.throughput                     7  thrpt    3   11817,208 ±   24794,567  ops/s
    * [info] PsacInProgressSizeBench.throughput                     8  thrpt    3  335934,126 ±  172563,745  ops/s
    *
    * met money
    * [info] PsacInProgressSizeBench.throughput                     1  thrpt    3  760385,493 ± 1263311,283  ops/s
    * [info] PsacInProgressSizeBench.throughput                     2  thrpt    3  438687,057 ±  642717,609  ops/s
    * [info] PsacInProgressSizeBench.throughput                     3  thrpt    3  221796,787 ±  862026,439  ops/s
    * [info] PsacInProgressSizeBench.throughput                     4  thrpt    3  123105,622 ±  422208,776  ops/s
    * [info] PsacInProgressSizeBench.throughput                     5  thrpt    3   87941,715 ±   67769,364  ops/s
    * [info] PsacInProgressSizeBench.throughput                     6  thrpt    3   50328,829 ±   51943,852  ops/s
    * [info] PsacInProgressSizeBench.throughput                     7  thrpt    3   26775,772 ±   22407,664  ops/s
    * [info] PsacInProgressSizeBench.throughput                     8  thrpt    3  318878,989 ±  143507,050  ops/s
    */
  @Benchmark
  @BenchmarkMode(Array(Mode.Throughput))
  @OutputTimeUnit(TimeUnit.SECONDS)
  def throughput(bh: Blackhole): Unit = {
    dynamic(bh)
  }

  /**
    * [info] Benchmark                                      (inProgressSize)    Mode     Cnt  Score    Error  Units
    * [info] PsacInProgressSizeBench.sample                                1  sample  890876  0.002 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   1  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   1  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   1  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   1  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   1  sample          0.006           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  1  sample          0.020           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 1  sample          0.051           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   1  sample          2.130           ms/op
    * [info] PsacInProgressSizeBench.sample                                2  sample  760001  0.005 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   2  sample          0.004           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   2  sample          0.005           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   2  sample          0.005           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   2  sample          0.005           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   2  sample          0.019           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  2  sample          0.031           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 2  sample          0.100           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   2  sample          1.532           ms/op
    * [info] PsacInProgressSizeBench.sample                                3  sample  652523  0.012 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   3  sample          0.010           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   3  sample          0.011           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   3  sample          0.011           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   3  sample          0.018           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   3  sample          0.028           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  3  sample          0.047           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 3  sample          0.695           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   3  sample          2.638           ms/op
    * [info] PsacInProgressSizeBench.sample                                4  sample  588946  0.025 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   4  sample          0.023           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   4  sample          0.023           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   4  sample          0.033           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   4  sample          0.040           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   4  sample          0.049           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  4  sample          0.081           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 4  sample          0.792           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   4  sample          6.742           ms/op
    * [info] PsacInProgressSizeBench.sample                                5  sample  511035  0.059 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   5  sample          0.052           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   5  sample          0.053           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   5  sample          0.073           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   5  sample          0.081           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   5  sample          0.104           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  5  sample          0.159           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 5  sample          1.268           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   5  sample          5.849           ms/op
    * [info] PsacInProgressSizeBench.sample                                6  sample  230039  0.130 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   6  sample          0.114           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   6  sample          0.119           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   6  sample          0.151           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   6  sample          0.165           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   6  sample          0.217           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  6  sample          0.312           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 6  sample          1.454           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   6  sample          9.372           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   7  sample          0.254           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   7  sample          0.299           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   7  sample          0.337           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   7  sample          0.352           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   7  sample          0.483           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  7  sample          1.077           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 7  sample          1.874           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   7  sample          5.710           ms/op
    * [info] PsacInProgressSizeBench.sample                                8  sample  860814  0.002 ±  0.001  ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   8  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   8  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   8  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   8  sample          0.002           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   8  sample          0.005           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  8  sample          0.019           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 8  sample          0.043           ms/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   8  sample          1.884           ms/op
    *
    * [info] Benchmark                                      (inProgressSize)    Mode     Cnt       Score   Error  Units
    * [info] PsacInProgressSizeBench.sample                                1  sample  740849       3,786 ± 0,114  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   1  sample               2,088          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   1  sample               2,464          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   1  sample               3,812          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   1  sample               4,632          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   1  sample              20,608          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  1  sample             160,038          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 1  sample            1110,016          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   1  sample            8519,680          us/op
    * [info] PsacInProgressSizeBench.sample                                2  sample  839699      15,968 ± 0,781  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   2  sample               4,928          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   2  sample               8,784          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   2  sample              12,720          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   2  sample              21,376          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   2  sample              71,296          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  2  sample            1132,544          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 2  sample           10126,295          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   2  sample           44892,160          us/op
    * [info] PsacInProgressSizeBench.sample                                3  sample  441289      39,825 ± 3,242  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   3  sample              12,096          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   3  sample              20,672          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   3  sample              36,928          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   3  sample              50,048          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   3  sample             136,448          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  3  sample            4535,992          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 3  sample           20437,729          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   3  sample          182190,080          us/op
    * [info] PsacInProgressSizeBench.sample                                4  sample  366155      55,641 ± 1,196  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   4  sample              26,720          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   4  sample              44,800          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   4  sample              71,552          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   4  sample              93,440          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   4  sample             253,440          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  4  sample            1684,865          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 4  sample           10082,458          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   4  sample           36569,088          us/op
    * [info] PsacInProgressSizeBench.sample                                5  sample  205053     146,057 ± 3,687  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   5  sample              60,480          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   5  sample             104,960          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   5  sample             184,576          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   5  sample             244,224          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   5  sample             758,784          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  5  sample            6673,826          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 5  sample           22723,723          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   5  sample           62324,736          us/op
    * [info] PsacInProgressSizeBench.sample                                6  sample   94616     316,674 ± 9,266  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   6  sample             127,872          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   6  sample             209,920          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   6  sample             389,632          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   6  sample             525,312          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   6  sample            1758,536          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  6  sample           11713,167          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 6  sample           36256,193          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   6  sample           54132,736          us/op
    * [info] PsacInProgressSizeBench.sample                                7  sample   53512     560,128 ± 8,922  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   7  sample             305,152          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   7  sample             449,024          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   7  sample             799,744          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   7  sample             989,184          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   7  sample            2025,206          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  7  sample            8036,139          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 7  sample           32131,278          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   7  sample           42008,576          us/op
    * [info] PsacInProgressSizeBench.sample                                8  sample  843286       4,264 ± 0,175  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   8  sample               2,332          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   8  sample               2,672          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   8  sample               4,176          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   8  sample               5,080          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   8  sample              19,072          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  8  sample             191,415          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 8  sample            1598,815          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   8  sample           17498,112          us/op
    *
    * swat
    * [info] Benchmark                                      (inProgressSize)    Mode     Cnt     Score   Error  Units
    * [info] PsacInProgressSizeBench.sample                                1  sample  940313     2.058 ± 0.035  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   1  sample             1.784          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   1  sample             1.854          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   1  sample             1.906          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   1  sample             2.002          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   1  sample             5.232          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  1  sample            19.766          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 1  sample            49.786          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   1  sample          7774.208          us/op
    * [info] PsacInProgressSizeBench.sample                                2  sample  778813     4.866 ± 0.032  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   2  sample             4.296          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   2  sample             4.416          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   2  sample             4.568          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   2  sample             4.840          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   2  sample            19.008          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  2  sample            30.598          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 2  sample            99.072          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   2  sample          1640.448          us/op
    * [info] PsacInProgressSizeBench.sample                                3  sample  646086    11.656 ± 0.053  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   3  sample            10.336          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   3  sample            10.624          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   3  sample            11.040          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   3  sample            18.496          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   3  sample            28.192          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  3  sample            47.546          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 3  sample           702.865          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   3  sample          2899.968          us/op
    * [info] PsacInProgressSizeBench.sample                                4  sample  590684    25.391 ± 0.066  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   4  sample            22.592          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   4  sample            23.168          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   4  sample            32.832          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   4  sample            39.680          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   4  sample            48.448          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  4  sample            80.256          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 4  sample           817.867          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   4  sample          3387.392          us/op
    * [info] PsacInProgressSizeBench.sample                                5  sample  511089    58.601 ± 0.125  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   5  sample            51.968          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   5  sample            52.864          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   5  sample            73.600          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   5  sample            80.640          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   5  sample           102.272          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  5  sample           152.553          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 5  sample          1265.441          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   5  sample          9650.176          us/op
    * [info] PsacInProgressSizeBench.sample                                6  sample  235106   127.444 ± 0.248  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   6  sample           110.976          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   6  sample           115.840          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   6  sample           148.224          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   6  sample           162.048          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   6  sample           212.992          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  6  sample           309.084          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 6  sample          1430.287          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   6  sample          3821.568          us/op
    * [info] PsacInProgressSizeBench.sample                                7  sample   98118   305.442 ± 0.616  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   7  sample           255.488          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   7  sample           299.008          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   7  sample           334.848          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   7  sample           348.672          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   7  sample           429.056          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  7  sample          1089.292          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 7  sample          1903.461          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   7  sample          6144.000          us/op
    * [info] PsacInProgressSizeBench.sample                                8  sample  928640     2.069 ± 0.022  us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.00                   8  sample             1.864          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.50                   8  sample             1.920          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.90                   8  sample             1.966          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.95                   8  sample             1.998          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.99                   8  sample             3.984          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.999                  8  sample            19.072          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p0.9999                 8  sample            39.359          us/op
    * [info] PsacInProgressSizeBench.sample:sample·p1.00                   8  sample          2646.016          us/op
    */
  @Benchmark
  @BenchmarkMode(Array(Mode.SampleTime))
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  def sample(bh: Blackhole): Unit = {
    dynamic(bh)
  }
}
