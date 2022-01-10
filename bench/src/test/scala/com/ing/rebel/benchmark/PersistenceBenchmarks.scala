package com.ing.rebel.benchmark

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.Account.OpenAccount
import com.ing.rebel.{Iban, RebelDomainEvent}
import com.typesafe.config.Config
import org.openjdk.jmh.annotations._
import squants.market.EUR

import scala.concurrent.Await
import scala.concurrent.duration._
import BenchmarkSetup._
import com.ing.rebel.messages.{EntityCommandSuccess, RebelCommand}

import scala.collection.immutable

/**
  * Run with
  * sbt "bench/jmh:run -rf -i 10 -wi 10 -f 2 -t 1 com.ing.rebel.benchmark.*PersistenceBenchmark"
  */
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@OperationsPerInvocation(nrOfMessages)
abstract class PersistenceBenchmark extends BenchmarkSetup {

  var count = 1

  @Benchmark
  def dummySharding(): Int = {
    count += 1
    messages.foreach(i => DummySharding(system).
      tell(s"${i.toString}-${count.toString}", RebelCommand(RebelDomainEvent(Dummy.OnlyCommand)))(probe.ref))
    receiveAndAssert()
  }

  @Benchmark
  def simpleSharding(): Int = {
    count += 1
    messages.foreach(i => SimpleSharding(system).
      tell(s"${i.toString}-${count.toString}", RebelCommand(RebelDomainEvent(Simple.OnlyCommand)))(probe.ref))
    receiveAndAssert()
  }

//  @Benchmark
//  @OperationsPerInvocation(nrOfMessages*2)
//  def simpleSharding2(): Int = {
//    count += 1
//    val nr = nrOfMessages * 2
//    (1 to nr).toArray.foreach(i => SimpleSharding(system).tell(s"${i.toString}-${count.toString}", SimpleActor.OnlyCommand)(probe.ref))
//    val receivedMessages = probe.receiveN(nr, 20.seconds)
//    val result = receivedMessages.count(_.isInstanceOf[CommandSuccess])
//    assert(result == nr, s"$result was not equal to $nr: $receivedMessages")
//    result
//  }

  @Benchmark
  def simpleShardingWithPersistence(): Int = {
    count += 1
    messages.foreach(i => SimpleShardingWithPersistence(system).
      tell(s"${i.toString}-${count.toString}", RebelCommand(RebelDomainEvent(SimpleWithPersistence.OnlyCommand)))(probe.ref))
    receiveAndAssert()
  }

  @Benchmark
  def openAccount(): Int = {
    count += 1
    messages.foreach { i =>
      val iban = Iban(s"${i.toString}-${count.toString}")
      //noinspection ScalaStyle
      val openAccount: OpenAccount = OpenAccount(EUR(100))
      AccountSharding(system).tell(iban, RebelCommand(RebelDomainEvent(openAccount)))(probe.ref)
    }
    receiveAndAssert()
  }

}

class NoOpPersistenceBenchmark extends PersistenceBenchmark with NoOpPersistenceConfig

class InMemoryPersistenceBenchmark extends PersistenceBenchmark with InMemoryPersistenceConfig

class LevelDbPersistenceBenchmark extends PersistenceBenchmark with LevelDbConfig

class CassandraPersistenceBenchmark extends PersistenceBenchmark with CassandraConfig