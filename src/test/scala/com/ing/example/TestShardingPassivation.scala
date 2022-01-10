package com.ing.example

import akka.actor.{ExtendedActorSystem, ExtensionId, ExtensionIdProvider, Props}
import com.ing.example.sharding.AccountSharding
import com.ing.rebel.actors.RebelFSMActor

import scala.concurrent.duration._

object TestShardingPassivation extends ExtensionId[TestShardingPassivation] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): TestShardingPassivation = new TestShardingPassivation(system)

  override def lookup(): TestShardingPassivation.type = TestShardingPassivation
}
class TestShardingPassivation(system: ExtendedActorSystem) extends AccountSharding(system) {
  override val name: String = "Test"

  val passivationTimeOut: FiniteDuration = 2000.millis

  override val entryProps: Props = Props(new RebelFSMActor[Account.type] with AccountLogic {
    override def passivationTimeout: FiniteDuration = passivationTimeOut
  })
}
