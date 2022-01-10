package com.ing.corebank.rebel.sharding

import akka.actor.{Actor, ExtendedActorSystem, ExtensionId, Props}
import com.ing.corebank.rebel.sharding.Simple.OnlyCommand
import com.ing.rebel.RebelSharding.{RebelActorInitializer, RebelEntityHostProps, RebelShardingExtension}
import com.ing.rebel._
import com.ing.rebel.messages.EntityCommandSuccess
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import io.circe.generic.auto._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Decoder, Encoder}

import scala.reflect.ClassTag

object Simple extends Specification {
  sealed trait Event extends SpecificationEvent
  case object OnlyCommand extends Event

  val props = Props(new SimpleActor)
  override type Data = Unit
  case object State extends RebelState
  override type Key = String

  override def keyable: RebelKeyable[Key] = implicitly

  override type State = State.type
  override implicit val dataEncoder: Encoder[Unit] = deriveEncoder
  override implicit val stateEncoder: Encoder[State] = deriveEncoder
}
class SimpleActor extends Actor {
  override def receive: Receive = {
    case _ => sender ! EntityCommandSuccess(RebelDomainEvent(OnlyCommand))
  }
}

object SimpleSharding extends ExtensionId[SimpleSharding] {
  override def createExtension(system: ExtendedActorSystem): SimpleSharding = new SimpleSharding(system)
}
class SimpleSharding(system: ExtendedActorSystem) extends RebelShardingExtension(system) {
  override type Spec = Simple.type

  override implicit def specInfo: SpecificationInfo[Simple.type] = Simple.specInfo

  override def specClassTag: ClassTag[Simple.Event] = implicitly[ClassTag[Simple.Event]]

  override val eventDecoder: Decoder[spec.Event] = implicitly
  override val entryProps: RebelActorInitializer = Simple.props

  // disable stash
  override val syncActorImplementation: RebelEntityHostProps = (_, _) => Simple.props
}
