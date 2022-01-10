package com.ing.example.sharding

import akka.actor.{ActorRef, ExtendedActorSystem, ExtensionId, ExtensionIdProvider, Props}
import com.ing.example.ExternalAgreement
import com.ing.rebel.RebelSharding.RebelExternalShardingExtension
import com.ing.rebel.actors.ExternalSpecificationInfo
import com.ing.rebel.specification.SpecificationInfo
import io.circe.Decoder

import scala.reflect.ClassTag
//import com.ing.corebank.rebel.sharding.SimpleActor
import com.ing.example.ExternalAgreementActor
import com.ing.rebel.RebelKeyable._
import com.ing.rebel.RebelKeyable
import com.ing.rebel.RebelSharding.{RebelEntityHostProps, RebelShardingExtension}
import io.circe.generic.auto._
import scala.reflect.classTag


object ExternalAgreementSharding extends ExtensionId[ExternalAgreementSharding] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): ExternalAgreementSharding = new ExternalAgreementSharding(system)

  override def lookup(): ExternalAgreementSharding.type = ExternalAgreementSharding
}
class ExternalAgreementSharding(system: ExtendedActorSystem) extends RebelExternalShardingExtension(system) {
  override val name: String = "ExternalAgreement"

  override val entryProps: Props = ExternalAgreement.props

  //  override def syncActorImplementation(): RebelEntityHostProps =  (_,_) =>

  override type Spec = ExternalAgreement.type

  override def specClassTag: ClassTag[ExternalAgreement.Command] = classTag

  override val eventDecoder: Decoder[ExternalAgreement.Command] = implicitly

  override implicit def specInfo: ExternalSpecificationInfo[ExternalAgreement.type] = ExternalAgreement.specInfo
}


