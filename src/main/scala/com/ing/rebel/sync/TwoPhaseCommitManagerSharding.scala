package com.ing.rebel.sync

import akka.actor.{ActorRef, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.cluster.sharding.ShardRegion.{Msg, ShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import com.ing.rebel.RebelSharding
import com.ing.rebel.RebelSharding.{ShardingEnvelope, defaultShardResolver, shardCount}
import com.ing.rebel.messages.RebelMessage
import com.ing.rebel.sync.twophasecommit.{ContactPoint, ShardingContactPoint, TransactionManager}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import kamon.Kamon
import kamon.metric.Metric

object TwoPhaseCommitManagerSharding extends ExtensionId[TwoPhaseCommitManagerSharding] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): TwoPhaseCommitManagerSharding = new TwoPhaseCommitManagerSharding(system)

  override def lookup(): TwoPhaseCommitManagerSharding.type = TwoPhaseCommitManagerSharding

  val shardTypeName = "2pc"

  def contactPoint(transactionId: TransactionId): ContactPoint = ShardingContactPoint(shardTypeName, transactionId: TransactionId)
}

class TwoPhaseCommitManagerSharding(system: ExtendedActorSystem) extends Extension {

  import TwoPhaseCommitManagerSharding.shardTypeName

  val shardShardIdLookupCounter: Metric.Counter = Kamon.counter(s"shardid-$shardTypeName")

  val region: ActorRef = {
    ClusterSharding(system).start(
      shardTypeName,
      TransactionManager.props.withMailbox("rebel.stash-capacity-mailbox"), // TODO is this what we want for the 2PC manager?
      // Use remember entities to make sure coordinator recovers
      ClusterShardingSettings(system), // .withRememberEntities(true),
      RebelSharding.defaultIdExtractor,
      extractShardId = { m: Msg =>
        val result: ShardId = RebelSharding.defaultShardResolver(shardCount)(m)
        val counter = m match {
          case ShardingEnvelope(key, mess) =>
            shardShardIdLookupCounter.withTag("shardId", result).withTag("msgType", mess.getClass.getSimpleName)
          case _                           => shardShardIdLookupCounter.withTag("shardId", result)
        }
        counter.increment()
        result
      }
    )
  }

  def tellManager(transactionId: TransactionId, msg: RebelMessage)(implicit sender: ActorRef): Unit = {
    region.tell(ShardingEnvelope(transactionId, msg), sender)
  }

}
