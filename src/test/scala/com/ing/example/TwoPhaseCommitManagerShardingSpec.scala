package com.ing.example

import akka.actor.{Actor, ActorIdentity, ActorRef, ActorSelection, ActorSystem, Identify, PoisonPill, Props}
import akka.cluster.sharding.ClusterSharding
import akka.contrib.pattern.ReceivePipeline
import akka.testkit.TestProbe
import akka.util
import com.ing.example.sharding.AccountSharding
import com.ing.rebel.RebelSharding.{RebelShardingExtension, ShardingEnvelope}
import com.ing.rebel.messages.{CheckPreconditions, CheckPreconditionsResult, EntityCommandSuccess, RebelCommand}
import com.ing.rebel.sync.TwoPhaseCommitManagerSharding
import com.ing.rebel.sync.TwoPhaseCommitManagerSharding.shardTypeName
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.sync.twophasecommit.{ActorRefContactPoint, EntityHost, TransactionManager, TwoPhaseCommit}
import com.ing.rebel.util.{LoggingInterceptor, MessageLogger}
import com.ing.rebel.{Iban, Ibans, RebelConditionCheck, RebelDomainEvent, messages}
import com.ing.util.{IsolatedCluster, MessageLoggerAutoPilot}
import com.typesafe.config.ConfigFactory
import io.circe.generic.auto._
import com.ing.rebel.util.CirceSupport._
import org.joda.time.DateTime
import org.scalatest.Matchers
import squants.market.{EUR, Money}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TwoPhaseCommitManagerShardingSpec extends IsolatedCluster(
  extraConfig = ConfigFactory.parseString(
    "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\"\n" +
      "akka.persistence.snapshot-store.plugin = \"akka.persistence.snapshot-store.local\"")
) with MessageLoggerAutoPilot with Matchers {

  it should "do a whole 2pc when coordinator fails and recovers" in {
    system.actorOf(MessageLogger.props("target/2pc-coordinator-fails.html"))
    addTimer()

    val transId = TransactionId("transId1")
    val coordinator = TwoPhaseCommitManagerSharding.contactPoint(transId)

    val participant = TestProbe("participant")

    val initialize = Initialize(transId, Set(
      SyncAction(ParticipantId(transId, Ibans.testIban1.toString),
        ActorRefContactPoint(participant.ref),
        RebelDomainEvent(Account.OpenAccount(Ibans.testIban1, EUR(100)))
    )))

    coordinator.tell(initialize)
    val voteRequest = participant.expectMsgType[VoteRequest]
    val coordinatorRef: ActorRef = participant.lastSender
    system.stop(coordinatorRef)

    coordinator.tell(VoteCommit(transId, voteRequest.participantId, Set()))(system, participant.ref)
    // no response because dead
    participant.expectNoMessage(1.second)
    // retry
    coordinator.tell(VoteCommit(transId, voteRequest.participantId, Set()))(system, participant.ref)
    participant.expectMsgType[GlobalCommit]
  }

  it should "stop after a transaction is completed and stay down" in {
    val transId = TransactionId("transId1")
    val coordinator = TwoPhaseCommitManagerSharding.contactPoint(transId)

    val participant = TestProbe("participant")
    val watcher = TestProbe("watcher")

    val initialize = Initialize(transId, Set(
      SyncAction(ParticipantId(transId, Ibans.testIban1.toString),
        ActorRefContactPoint(participant.ref),
        RebelDomainEvent(Account.OpenAccount(Ibans.testIban1, EUR(100))))
    ))

    coordinator.tell(initialize)
    val voteRequest = participant.expectMsgType[VoteRequest]
    val coordinatorRef: ActorRef = participant.lastSender
    watcher.watch(coordinatorRef)

    coordinator.tell(VoteCommit(transId, voteRequest.participantId, Set()))(system, participant.ref)
    participant.expectMsgType[GlobalCommit]
    val ack = GlobalCommitAck(transId, voteRequest.participantId)
    expectMsg(TransactionResult(transId, RebelConditionCheck.success))
    coordinator.tell(ack)(system, participant.ref)
    watcher.expectTerminated(coordinatorRef)

    system.actorSelection(coordinatorRef.path).tell(Identify(1), watcher.ref)
    watcher.expectMsg(ActorIdentity(1, None))
    watcher.expectNoMessage(3.seconds)
    participant.expectNoMessage(3.seconds)
    expectNoMessage(3.seconds)
    system.actorSelection(coordinatorRef.path).tell(Identify(1), watcher.ref)
    watcher.expectMsg(ActorIdentity(1, None))
  }

}
