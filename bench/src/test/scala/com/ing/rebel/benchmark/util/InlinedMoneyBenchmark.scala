package com.ing.rebel.benchmark.util

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.simple_transaction.{Account, AccountLogic}
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, Opened}
import com.ing.rebel.sync.pathsensitive.DynamicPsacCommandDecider
import com.ing.rebel.{Iban, Initialised, RebelConditionCheck, RebelDomainEvent, RebelError}
import org.joda.time.DateTime
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.{EUR, Money}

/**
  * bench/jmh:run  -prof jmh.extras.JFR:dir=./target/  -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.util.InlinedMoneyBenchmark
  *
  * [info] Benchmark                             Mode  Cnt       Score        Error  Units
  * [info] InlinedMoneyBenchmark.finalVal       thrpt    3  147303,787 ±  76581,232  ops/s
  * [info] InlinedMoneyBenchmark.inline         thrpt    3  113149,111 ±  11546,486  ops/s
  * [info] InlinedMoneyBenchmark.inlineInt      thrpt    3   68759,609 ± 872181,243  ops/s
  * [info] InlinedMoneyBenchmark.normalVal      thrpt    3  144814,850 ± 178883,346  ops/s
  *
  * [info] InlinedMoneyBenchmark.finalVal       thrpt   10  121589,870 ± 24961,824  ops/s
  * [info] InlinedMoneyBenchmark.inline         thrpt   10   85303,457 ± 23803,312  ops/s
  * [info] InlinedMoneyBenchmark.inlineInt      thrpt   10   71620,431 ± 25645,665  ops/s
  * [info] InlinedMoneyBenchmark.normalVal      thrpt   10  122483,074 ± 29818,496  ops/s
  *
  * swat
  * [info] InlinedMoneyBenchmark.finalVal             thrpt   10  220813.453 ± 5243.565  ops/s
  * [info] InlinedMoneyBenchmark.inline               thrpt   10  164184.082 ± 3827.693  ops/s
  * [info] InlinedMoneyBenchmark.inlineInt            thrpt   10  167740.111 ± 4222.303  ops/s
  * [info] InlinedMoneyBenchmark.normalVal            thrpt   10  227157.821 ± 5634.482  ops/s
  * [info] InlinedMoneyBenchmark.privateFinalVal      thrpt   10  223835.423 ± 4476.218  ops/s
  * [info] InlinedMoneyBenchmark.privateVAl           thrpt   10  221385.283 ± 4364.801  ops/s
  *
  * [info] InlinedMoneyBenchmark.inline               thrpt   10  167141.821 ± 4371.719  ops/s
  * [info] InlinedMoneyBenchmark.inlineInt            thrpt   10  168352.813 ± 4493.865  ops/s
  * [info] InlinedMoneyBenchmark.privateFinalVal      thrpt   10  220965.376 ± 3742.938  ops/s
  * [info] InlinedMoneyBenchmark.normalVal            thrpt   10  221176.958 ± 4633.742  ops/s
  * [info] InlinedMoneyBenchmark.finalVal             thrpt   10  222224.454 ± 5569.143  ops/s
  * [info] InlinedMoneyBenchmark.privateVal           thrpt   10  229582.059 ± 4851.822  ops/s
  *
  * [info] InlinedMoneyBenchmark.inlineInt            thrpt   30  166633.769 ± 1418.122  ops/s
  * [info] InlinedMoneyBenchmark.inline               thrpt   30  166940.286 ± 2154.410  ops/s
  * [info] InlinedMoneyBenchmark.normalVal            thrpt   30  224063.969 ± 1709.074  ops/s
  * [info] InlinedMoneyBenchmark.finalVal             thrpt   30  226683.330 ± 1811.899  ops/s
  * [info] InlinedMoneyBenchmark.privateFinalVal      thrpt   30  226770.403 ± 2168.065  ops/s
  * [info] InlinedMoneyBenchmark.privateVal           thrpt   30  228283.005 ± 1773.143  ops/s
  **/
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class InlinedMoneyBenchmark {

  //  @Param(Array("1","2","3","4","5","6","7","8"))
  var inProgressSize: Int = 4

  object InlineLogic extends AccountLogic {
    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Account.Event, RebelConditionCheck] = {
      case Deposit(amount) => checkPreCondition(amount > EUR(0.00), "amount > EUR 0.00")
      case default         => super.checkPreConditions(data, now)(default)
    }
  }

  object InlineIntLogic extends AccountLogic {
    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Account.Event, RebelConditionCheck] = {
      case Deposit(amount) => checkPreCondition(amount > EUR(0), "amount > EUR 0.00")
      case default         => super.checkPreConditions(data, now)(default)
    }
  }

  object ValLogic extends AccountLogic {
    val eur0: Money = EUR(0.00)

    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Account.Event, RebelConditionCheck] = {
      case Deposit(amount) =>
        checkPreCondition(amount > eur0, "amount > EUR 0.00")
      case default         => super.checkPreConditions(data, now)(default)
    }
  }

  object PrivateValLogic extends AccountLogic {
    private val eur0: Money = EUR(0.00)

    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Account.Event, RebelConditionCheck] = {
      case Deposit(amount) =>
        checkPreCondition(amount > eur0, "amount > EUR 0.00")
      case default         => super.checkPreConditions(data, now)(default)
    }
  }

  object FinalValLogic extends AccountLogic {
    final val eur0: Money = EUR(0.00)

    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Account.Event, RebelConditionCheck] = {
      case Deposit(amount) =>
        checkPreCondition(amount > eur0, "amount > EUR 0.00")
      case default         => super.checkPreConditions(data, now)(default)
    }
  }

  object PrivateFinalValLogic extends AccountLogic {
    private final val eur0: Money = EUR(0.00)

    override def checkPreConditions(data: RData, now: DateTime): PartialFunction[Account.Event, RebelConditionCheck] = {
      case Deposit(amount) =>
        checkPreCondition(amount > eur0, "amount > EUR 0.00")
      case default         => super.checkPreConditions(data, now)(default)
    }
  }

  val inlineDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, InlineLogic)
  val inlineIntDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, InlineIntLogic)
  val valDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, ValLogic)
  val privateValDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, PrivateValLogic)
  val finalValDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, FinalValLogic)
  val privateFinalValLogicDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, PrivateFinalValLogic)

  def benchmark(decider: DynamicPsacCommandDecider[Account.type], bh: Blackhole): Unit = {
    val inProgress = (1 to inProgressSize).map(i => RebelDomainEvent(Deposit(EUR(1))))
    bh.consume(decider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(100)))),
      inProgress, RebelDomainEvent(Deposit(EUR(1)))))
  }

  @Benchmark
  def inline(bh: Blackhole): Unit = {
    benchmark(inlineDecider, bh)
  }

  @Benchmark
  def inlineInt(bh: Blackhole): Unit = {
    benchmark(inlineIntDecider, bh)
  }

  @Benchmark
  def normalVal(bh: Blackhole): Unit = {
    benchmark(valDecider, bh)
  }

  @Benchmark
  def privateVal(bh: Blackhole): Unit = {
    benchmark(privateValDecider, bh)
  }

  @Benchmark
  def finalVal(bh: Blackhole): Unit = {
    benchmark(finalValDecider, bh)
  }

  @Benchmark
  def privateFinalVal(bh: Blackhole): Unit = {
    benchmark(privateFinalValLogicDecider, bh)
  }
}
