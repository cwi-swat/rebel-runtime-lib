package com.ing.rebel.sync.twophasecommit

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.util.Timeout
import com.ing.rebel.RebelSharding.ShardingEnvelope
import com.ing.rebel.messages.RebelMessage
import com.ing.rebel.specification.Specification
import com.ing.rebel.sync.twophasecommit.ContactPoint.{RebelKey, ShardingTypeName}

import scala.concurrent.Future

object ContactPoint {
  // Type name for sharding as used by Akka
  type ShardingTypeName = String

  type RebelKey = Any
}

sealed trait ContactPoint {
  def tell(message: RebelMessage)(implicit system: ActorSystem, sender: ActorRef): Unit

  def ask(message: RebelMessage)(implicit system: ActorSystem, sender: ActorRef, timeout: Timeout): Future[Any]
}

object ShardingContactPoint {
  def apply(typeName: Specification, key:RebelKey): ShardingContactPoint = ShardingContactPoint(typeName.specificationName, key)
}

// todo Any as id? Something more strict or not?
case class ShardingContactPoint(typeName: ShardingTypeName, key: RebelKey) extends ContactPoint {
  // TODO use stronglier typed references to the specification entities et al, office pattern?
  override def tell(message: RebelMessage)(implicit system: ActorSystem, sender: ActorRef): Unit = {
    system.log.debug("Sending message `{}` from sender `{}` to `{}` `{}`", message, sender, typeName, key)
    // Should use the local actor system (9-11-17: ????)
    // Should be reasonably fast because it reuses the existing sharding proxy
    ClusterSharding(system).shardRegion(typeName).tell(ShardingEnvelope(key, message), sender)
  }

  override def ask(message: RebelMessage)(implicit system: ActorSystem, sender: ActorRef, timeout: Timeout): Future[Any] = {
    akka.pattern.ask(ClusterSharding(system).shardRegion(typeName), ShardingEnvelope(key, message), sender)
  }
}

case class ActorRefContactPoint(actorRef: ActorRef) extends ContactPoint {
  override def tell(message: RebelMessage)(implicit system: ActorSystem, sender: ActorRef): Unit =
    actorRef.tell(message, sender)

  override def ask(message: RebelMessage)(implicit system: ActorSystem, sender: ActorRef, timeout: Timeout): Future[Any] = {
    akka.pattern.ask(actorRef, message, sender)(timeout)
  }
}