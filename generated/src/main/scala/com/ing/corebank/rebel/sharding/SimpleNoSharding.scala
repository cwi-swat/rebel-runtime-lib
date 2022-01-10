package com.ing.corebank.rebel.sharding

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, ExtensionId, Props}
import akka.util.Timeout
import com.ing.corebank.rebel.sharding.SimpleNo.OnlyCommand
import com.ing.rebel.RebelSharding.{RebelActorInitializer, RebelEntityHostProps, RebelShardingExtension}
import com.ing.rebel._
import com.ing.rebel.messages.{EntityCommandSuccess, RebelMessage}
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import io.circe.generic.auto._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Decoder, Encoder}

import scala.concurrent.Future
import scala.reflect.ClassTag

object SimpleNo extends Specification {
  sealed trait Event extends SpecificationEvent
  case object OnlyCommand extends Event

  val props = Props(new SimpleNoActor)
  override type Data = Unit
  case object State extends RebelState
  override type Key = String

  override def keyable: RebelKeyable[Key] = implicitly

  override type State = State.type
  override implicit val dataEncoder: Encoder[Unit] = deriveEncoder
  override implicit val stateEncoder: Encoder[State] = deriveEncoder
}
class SimpleNoActor extends Actor {
  override def receive: Receive = {
    case _ => sender ! EntityCommandSuccess(RebelDomainEvent(OnlyCommand))
  }
}

object SimpleNoSharding extends ExtensionId[SimpleNoSharding] {
  override def createExtension(system: ExtendedActorSystem): SimpleNoSharding = new SimpleNoSharding(system)
}
class SimpleNoSharding(system: ExtendedActorSystem) extends RebelShardingExtension(system) {
  override type Spec = SimpleNo.type

  override implicit def specInfo: SpecificationInfo[SimpleNo.type] = SimpleNo.specInfo

  override def specClassTag: ClassTag[SimpleNo.Event] = implicitly[ClassTag[SimpleNo.Event]]

  // ignore sharding in total
  override def ask(key: String, message: RebelMessage)(implicit sender: ActorRef, timeout: Timeout): Future[Any] = {
    val actor = system.actorOf(SimpleNo.props)
    akka.pattern.ask(actor, message)
  }

  override def tell(key: String, message: RebelMessage)(implicit sender: ActorRef): Unit = {
    val actor = system.actorOf(SimpleNo.props)
    actor.tell(message, sender)
  }

  override val eventDecoder: Decoder[spec.Event] = implicitly
  override val entryProps: RebelActorInitializer = SimpleNo.props

  // disable stash
  override val syncActorImplementation: RebelEntityHostProps = (_, _) => SimpleNo.props
}
