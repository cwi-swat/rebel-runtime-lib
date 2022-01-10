package com.ing.rebel.benchmark.util

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.{Benchmark, BenchmarkMode, Mode, OutputTimeUnit, Scope, State}
import org.openjdk.jmh.infra.Blackhole

/**
  * bench/jmh:run  -prof jmh.extras.JFR:dir=./target/  -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.util.FunctionVsMethodBenchmark
  *
  * [info] Benchmark                                    Mode  Cnt       Score        Error  Units
  * [info] FunctionVsMethodBenchmark.classMethod       thrpt    3  625227,042 ± 107910,001  ops/s
  * [info] FunctionVsMethodBenchmark.classMethod:JFR   thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.function          thrpt    3  544209,037 ± 716945,550  ops/s
  * [info] FunctionVsMethodBenchmark.function:JFR      thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.objectMethod      thrpt    3  617063,817 ± 301537,843  ops/s
  * [info] FunctionVsMethodBenchmark.objectMethod:JFR  thrpt              NaN                 N/A
  *
  * [info] FunctionVsMethodBenchmark.dependentFunction      thrpt    3  359270,388 ± 3773539,766  ops/s
  * [info] FunctionVsMethodBenchmark.dependentFunction:JFR  thrpt              NaN                  N/A
  * [info] FunctionVsMethodBenchmark.function               thrpt    3  456622,371 ±  191070,850  ops/s
  * [info] FunctionVsMethodBenchmark.function:JFR           thrpt              NaN                  N/A
  * [info] FunctionVsMethodBenchmark.partialFunction        thrpt    3  391697,730 ± 1555585,828  ops/s
  * [info] FunctionVsMethodBenchmark.partialFunction:JFR    thrpt              NaN                  N/A
  *
  * [info] FunctionVsMethodBenchmark.classMethod                   thrpt    3  612078,938 ± 522429,488  ops/s
  * [info] FunctionVsMethodBenchmark.classMethod:JFR               thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.dependentFunction             thrpt    3  460409,630 ± 119748,991  ops/s
  * [info] FunctionVsMethodBenchmark.dependentFunction:JFR         thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction      thrpt    3  457346,602 ±  94777,232  ops/s
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction:JFR  thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.function                      thrpt    3  442035,307 ± 139603,799  ops/s
  * [info] FunctionVsMethodBenchmark.function:JFR                  thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.objectMethod                  thrpt    3  613401,708 ± 467825,283  ops/s
  * [info] FunctionVsMethodBenchmark.objectMethod:JFR              thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.partialFunction               thrpt    3  457123,657 ±  49311,138  ops/s
  * [info] FunctionVsMethodBenchmark.partialFunction:JFR           thrpt              NaN                 N/A
  *
  * [info] FunctionVsMethodBenchmark.classMethod                   thrpt   10  341761,287 ±  87181,305  ops/s
  * [info] FunctionVsMethodBenchmark.classMethod:JFR               thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.dependentFunction             thrpt   10  362124,343 ±  93084,026  ops/s
  * [info] FunctionVsMethodBenchmark.dependentFunction:JFR         thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction      thrpt   10  418803,347 ±  56516,614  ops/s
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction:JFR  thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.function                      thrpt   10  370145,732 ± 102974,142  ops/s
  * [info] FunctionVsMethodBenchmark.function:JFR                  thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.objectMethod                  thrpt   10  423818,814 ± 125279,389  ops/s
  * [info] FunctionVsMethodBenchmark.objectMethod:JFR              thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.partialFunction               thrpt   10  318209,866 ± 130759,116  ops/s
  * [info] FunctionVsMethodBenchmark.partialFunction:JFR           thrpt              NaN                 N/A
  *
  * [info] FunctionVsMethodBenchmark.classMethod                   thrpt   10  596731,628 ± 31034,789  ops/s
  * [info] FunctionVsMethodBenchmark.classMethod:JFR               thrpt              NaN                N/A
  * [info] FunctionVsMethodBenchmark.dependentFunction             thrpt   10  427394,574 ± 43247,340  ops/s
  * [info] FunctionVsMethodBenchmark.dependentFunction:JFR         thrpt              NaN                N/A
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction      thrpt   10  485830,484 ± 33386,913  ops/s
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction:JFR  thrpt              NaN                N/A
  * [info] FunctionVsMethodBenchmark.function                      thrpt   10  472181,254 ± 31104,236  ops/s
  * [info] FunctionVsMethodBenchmark.function:JFR                  thrpt              NaN                N/A
  * [info] FunctionVsMethodBenchmark.objectMethod                  thrpt   10  645763,492 ± 16028,412  ops/s
  * [info] FunctionVsMethodBenchmark.objectMethod:JFR              thrpt              NaN                N/A
  * [info] FunctionVsMethodBenchmark.partialFunction               thrpt   10  466620,949 ± 38407,822  ops/s
  * [info] FunctionVsMethodBenchmark.partialFunction:JFR           thrpt              NaN                N/A
  *
  * [info] FunctionVsMethodBenchmark.classMethod                   thrpt   10  499275,135 ± 184880,282  ops/s
  * [info] FunctionVsMethodBenchmark.classMethod:JFR               thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.dependentFunction             thrpt   10  391800,034 ±  78773,826  ops/s
  * [info] FunctionVsMethodBenchmark.dependentFunction:JFR         thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction      thrpt   10  472923,029 ±   3765,451  ops/s
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction:JFR  thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.function                      thrpt   10  495133,683 ±   2849,708  ops/s
  * [info] FunctionVsMethodBenchmark.function:JFR                  thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.objectMethod                  thrpt   10  526956,960 ±   2594,713  ops/s
  * [info] FunctionVsMethodBenchmark.objectMethod:JFR              thrpt              NaN                 N/A
  * [info] FunctionVsMethodBenchmark.partialFunction               thrpt   10  429630,559 ± 145674,648  ops/s
  * [info] FunctionVsMethodBenchmark.partialFunction:JFR           thrpt              NaN                 N/A
  *
  * swat
  * [info] Benchmark                                            Mode  Cnt       Score      Error  Units
  * [info] FunctionVsMethodBenchmark.classMethod               thrpt   10  818647.885 ± 7123.365  ops/s
  * [info] FunctionVsMethodBenchmark.objectMethod              thrpt   10  789623.188 ± 4479.933  ops/s
  * [info] FunctionVsMethodBenchmark.dependentFunction         thrpt   10  645453.231 ± 2501.441  ops/s
  * [info] FunctionVsMethodBenchmark.function                  thrpt   10  640796.264 ± 3016.381  ops/s
  * [info] FunctionVsMethodBenchmark.partialFunction           thrpt   10  633571.558 ± 1935.488  ops/s
  * [info] FunctionVsMethodBenchmark.dependentPartialFunction  thrpt   10  631031.449 ± 2020.462  ops/s
  */
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class FunctionVsMethodBenchmark {


  val func: Int => String = {
    case 1 => "ONE"
    case i => i.toString
  }

  val partialFunc: PartialFunction[Int, String] = {
    case 1 => "ONE"
    case i => i.toString
  }

  def dependentFunc(oneString: String): Int => String = {
    case 1 => oneString
    case i => i.toString
  }

  def dependentPartialFunc(oneString: String): PartialFunction[Int, String] = {
    case 1 => oneString
    case i => i.toString
  }

  @Benchmark
  def function(bh: Blackhole): Unit = {
    bh.consume((1 to 100).map(i => func(i)))
  }

  @Benchmark
  def partialFunction(bh: Blackhole): Unit = {
    bh.consume((1 to 100).map(i => partialFunc(i)))
  }

  @Benchmark
  def dependentFunction(bh: Blackhole): Unit = {
    bh.consume((1 to 100).map(i => dependentFunc("ONE")(i)))
  }

  @Benchmark
  def dependentPartialFunction(bh: Blackhole): Unit = {
    bh.consume((1 to 100).map(i => dependentPartialFunc("ONE")(i)))
  }

  object Holder {
    def meth(i: Int): String = i match {
      case 1 => "ONE"
      case n => n.toString
    }
  }

  class HolderClass {
    def meth(i: Int): String = i match {
      case 1 => "ONE"
      case n => n.toString
    }
  }

  @Benchmark
  def objectMethod(bh: Blackhole): Unit = {
    bh.consume((1 to 100).map(i => Holder.meth(i)))
  }

  @Benchmark
  def classMethod(bh: Blackhole): Unit = {
    bh.consume((1 to 100).map(i => new HolderClass().meth(i)))
  }

}
