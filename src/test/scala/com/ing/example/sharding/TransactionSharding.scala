package com.ing.example.sharding

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import com.ing.example.Transaction
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel._
import com.ing.rebel.specification.SpecificationInfo
import com.ing.rebel.util.CirceSupport._
import io.circe.Decoder
import io.circe.generic.auto._

import scala.reflect.ClassTag
//import com.ing.rebel.util.ErrorAccumulatingCirceSupport._

object TransactionSharding extends ExtensionId[TransactionSharding] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): TransactionSharding = new TransactionSharding(system)

  override def lookup(): ExtensionId[_ <: Extension] = TransactionSharding
}
class TransactionSharding(system: ExtendedActorSystem)
  extends RebelShardingExtension(system) {
  override val entryProps: Props = Transaction.props
  override val name: String = "Transaction"

  implicit override def specInfo: SpecificationInfo[Transaction.type] = Transaction.specInfo

  override type Spec = Transaction.type

  override def specClassTag: ClassTag[Transaction.SpecEvent] = implicitly

  override val eventDecoder: Decoder[Transaction.SpecEvent] = implicitly
}