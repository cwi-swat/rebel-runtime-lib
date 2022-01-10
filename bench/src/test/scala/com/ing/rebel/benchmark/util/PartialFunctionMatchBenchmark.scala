package com.ing.rebel.benchmark.util

import java.util.concurrent.TimeUnit

import com.ing.corebank.rebel.simple_transaction.Account
import com.ing.corebank.rebel.simple_transaction.Account.Deposit
import com.ing.rebel.{Iban, Initialised, RebelDomainEvent, RebelError}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import squants.market.EUR

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
class PartialFunctionMatchBenchmark extends {

  private val specification: Account.Logic.type = Account.Logic

  val domainEvent = RebelDomainEvent(Deposit(EUR(20)))
  val incomingEvent = RebelDomainEvent(Deposit(EUR(10)))

  @Benchmark
  def liftIsDefinedBench(bh: Blackhole): Unit = {
    bh.consume(specification.failFastEvents.lift(domainEvent.specEvent, incomingEvent.specEvent).isDefined)
  }

  @Benchmark
  def isDefinedAtBench(bh: Blackhole): Unit = {
    bh.consume(specification.failFastEvents.isDefinedAt(domainEvent.specEvent, incomingEvent.specEvent))
  }

  @Benchmark
  def applyOrElseBench(bh: Blackhole): Unit = {
    bh.consume(specification.failFastEvents.
      applyOrElse((domainEvent.specEvent, incomingEvent.specEvent), (_: (specification.SpecEvent, specification.SpecEvent)) => false))
  }
}
