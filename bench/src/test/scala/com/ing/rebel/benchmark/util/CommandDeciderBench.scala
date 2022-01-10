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
  * bench/jmh:run  -prof jmh.extras.JFR:dir=./target/  -i 3 -wi 3 -f 1 com.ing.rebel.benchmark.util.CommandDeciderBench
[info] CommandDeciderBench.locking            thrpt    3  700984,365 ± 1398878,946  ops/s
[info] CommandDeciderBench.dynamic            thrpt    3  294444,121 ± 1259975,478  ops/s
[info] CommandDeciderBench.staticdynamic      thrpt    3  936589,028 ± 2840994,297  ops/s
  */

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class CommandDeciderBench {

  val dynamicDecider: DynamicPsacCommandDecider[Account.type] = new DynamicPsacCommandDecider(8, Account.Logic)
  val lockingDecider: TwoPLCommandDecider[Account.type] = new TwoPLCommandDecider(Account.Logic)
  val staticDynamicDecider: StaticCommandDecider[Account.type] = new StaticCommandDecider(8, Account.Logic)(dynamicDecider)
  val staticLockingDecider: StaticCommandDecider[Account.type] = new StaticCommandDecider(8, Account.Logic)(lockingDecider)

  @Benchmark
  def staticdynamic(bh: Blackhole): Unit = {
    bh.consume(staticDynamicDecider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(100)))),
      Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Deposit(EUR(10)))))
  }

  @Benchmark
  def staticlocking(bh: Blackhole): Unit = {
    bh.consume(staticLockingDecider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(100)))),
      Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Deposit(EUR(10)))))
  }

  @Benchmark
  def dynamic(bh: Blackhole): Unit = {
    bh.consume(dynamicDecider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(100)))),
      Seq(RebelDomainEvent(Deposit(EUR(20)))), RebelDomainEvent(Deposit(EUR(10)))))
  }

  // A bit for bacon and beans, because always returns Delay, by simple size check, so has to be repeated for comparison.
  // 2nd call is to make mimic same amount of finished transactions as others
  @Benchmark
  def locking(bh: Blackhole): Unit = {
    // only create once per transaction
    val deposit = RebelDomainEvent(Deposit(EUR(10)))
    bh.consume(lockingDecider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(100)))),
      Seq(RebelDomainEvent(Deposit(EUR(20)))), deposit))
    bh.consume(lockingDecider.allowCommand(Opened, Initialised(Account.Data(Some(EUR(80)))),
      Seq(), deposit))
  }
}
