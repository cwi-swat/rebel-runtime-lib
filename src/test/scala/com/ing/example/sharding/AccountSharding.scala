package com.ing.example.sharding

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import com.ing.example
import com.ing.example.Account
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel.specification.SpecificationInfo
import com.ing.rebel.util.CirceSupport._
import io.circe.Decoder
import io.circe.generic.auto._

import scala.reflect.ClassTag

object AccountSharding extends ExtensionId[AccountSharding] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): AccountSharding = new AccountSharding(system)

  override def lookup(): ExtensionId[_ <: Extension] = AccountSharding
}
class AccountSharding(system: ExtendedActorSystem) extends RebelShardingExtension(system) {
  override val entryProps: Props = Account.props

  override type Spec = Account.type

  implicit override def specInfo: SpecificationInfo[Account.type] = Account.specInfo

  //  override implicit def specInfo: SpecificationInfo[Account.type] = Account.specInfo

  override def specClassTag: ClassTag[example.Account.AccountEvent] = implicitly

  override val eventDecoder: Decoder[example.Account.AccountEvent] = implicitly
}