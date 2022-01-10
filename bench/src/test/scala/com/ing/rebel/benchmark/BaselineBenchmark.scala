package com.ing.rebel.benchmark

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.persistence.{PersistentActor, Recovery}
import org.openjdk.jmh.annotations._
import BenchmarkSetup._


class Ponger(respondAfter: Int) extends Actor {
  override def receive: Receive = {
    case `respondAfter` => sender() ! respondAfter
    case _ =>
  }
}

class PersistentPonger(id: String, enableRecovery: Boolean, respondAfter: Int) extends PersistentActor {
  override def recovery: Recovery = if (enableRecovery) super.recovery else Recovery.none

  override def receiveRecover: Receive = {
    case _ =>
  }

  override def receiveCommand: Receive = {
    case `respondAfter` => persist(respondAfter) {
      sender() ! _
    }
    case msg => persist(msg) {_ => }
  }

  override val persistenceId: String = s"PersistentPonger$id"
}

object Ponger {
  def props(respondAfter: Int): Props = Props(new Ponger(respondAfter))

  def persistentPongerProps(respondAfter: Int, id: String, enableRecovery: Boolean = true): Props = Props(new PersistentPonger(id, enableRecovery, respondAfter))
}

@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 10, time = 1700, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 20, time = 1700, timeUnit = TimeUnit.MILLISECONDS)
abstract class BaselineBenchmark extends BenchmarkSetup {

  var ponger: ActorRef = _
  var persistedPonger: ActorRef = _
  var persistedPongerWithoutRecovery: ActorRef = _
  var count = 0

  @Setup(Level.Iteration)
  def createPonger(): Unit = {
    ponger = system.actorOf(Ponger.props(lastMessage))
    count += 1
    persistedPonger = system.actorOf(Ponger.persistentPongerProps(lastMessage, count.toString))
    persistedPongerWithoutRecovery = system.actorOf(Ponger.persistentPongerProps(lastMessage, count.toString, enableRecovery = false))
  }

  @Benchmark
  @OperationsPerInvocation(nrOfMessages)
  def maxMessages(): Int = {
    messages.foreach(ponger.tell(_, probe.ref))
    probe.expectMsg(lastMessage)
  }

  @Benchmark
  @OperationsPerInvocation(nrOfMessages)
  def maxMessagesWithCreation(): Int = {
    val actor = system.actorOf(Ponger.props(lastMessage))
    messages.foreach { i =>
      actor.tell(i, probe.ref)
    }
    probe.expectMsg(lastMessage)
  }

  @Benchmark
  @OperationsPerInvocation(nrOfMessages)
  def maxPersistedMessages(): Int = {
    messages.foreach(persistedPonger.tell(_, probe.ref))
    probe.expectMsg(lastMessage)
  }

  @Benchmark
  @OperationsPerInvocation(nrOfMessages)
  def maxPersistedMessagesWithCreation(): Int = {
    val actor = system.actorOf(Ponger.persistentPongerProps(lastMessage, count.toString))
    messages.foreach { i =>
      actor.tell(i, probe.ref)
    }
    probe.expectMsg(lastMessage)
  }

  @Benchmark
  @OperationsPerInvocation(nrOfMessages)
  def maxPersistedMessagesWithoutRecovery(): Int = {
    messages.foreach(persistedPongerWithoutRecovery.tell(_, probe.ref))
    probe.expectMsg(lastMessage)
  }

  @Benchmark
  @OperationsPerInvocation(nrOfMessages)
  def maxPersistedMessagesWithCreationWithoutRecovery(): Int = {
    val actor = system.actorOf(Ponger.persistentPongerProps(lastMessage, count.toString, enableRecovery = false))
    messages.foreach(actor.tell(_, probe.ref))
    probe.expectMsg(lastMessage)
  }
}

class NoOpPersistenceBaselineBenchmark extends BaselineBenchmark with NoOpPersistenceConfig

class LevelDbBaselineBenchmark extends BaselineBenchmark with LevelDbConfig

class CassandraBaselineBenchmark extends BaselineBenchmark with CassandraConfig
