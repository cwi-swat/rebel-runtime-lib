package com.ing.rebel.benchmark

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.ing.rebel.RebelConditionCheck
import com.ing.rebel.sync.TwoPhaseCommitSpec.{createInitializeMsg, entityActor}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Initialize, TransactionResult}
import com.ing.rebel.sync.twophasecommit.{ActorRefContactPoint, TransactionManager, TransactionParticipant}
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime, Mode.SampleTime))
abstract class TwoPhaseCommitBenchmark extends BenchmarkSetup {

  var count = 0

  var requester: TestProbe = _
  var p1: ActorRef = _
  var coordinator: ActorRef = _
  var initializeMsg: Initialize = _

  @Setup(Level.Invocation)
  def setup2PC(): Unit = {
    requester = TestProbe()(system)
    val id = count // UUID.randomUUID().toString
    count += 1
    coordinator = system.actorOf(TransactionManager.props, s"coordinator-$id")
    p1 = system.actorOf(TransactionParticipant.props(ActorRefContactPoint(entityActor(_ => true)), ActorRefContactPoint(coordinator)), s"participant1-$id")
    //      requester.watch(coordinator)
    initializeMsg = createInitializeMsg(p1) //, a2)
  }

  @Benchmark
  def fullCycle(): TransactionResult = {
    requester.send(coordinator, initializeMsg)
    requester.expectMsg(TransactionResult(initializeMsg.transactionId, RebelConditionCheck.success))
  }
}

class NoOpPersistenceTwoPhaseCommitBenchmark extends TwoPhaseCommitBenchmark with NoOpPersistenceConfig

class LevelDbTwoPhaseCommitBenchmark extends TwoPhaseCommitBenchmark with LevelDbConfig

class CassandraTwoPhaseCommitBenchmark extends TwoPhaseCommitBenchmark with CassandraConfig