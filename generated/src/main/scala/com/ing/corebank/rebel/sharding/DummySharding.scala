package com.ing.corebank.rebel.sharding

import akka.actor.{ActorRef, ExtendedActorSystem, ExtensionId, Props}
import akka.util.Timeout
import com.ing.corebank.rebel.sharding.Dummy.OnlyCommand
import com.ing.rebel.RebelSharding.{RebelEntityHostProps, RebelShardingExtension}
import com.ing.rebel._
import com.ing.rebel.messages._
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import io.circe.generic.auto._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Decoder, Encoder}

import scala.concurrent.Future
import scala.reflect.{ClassTag, classTag}

object Dummy extends Specification {
  sealed trait Event extends SpecificationEvent
  case object OnlyCommand extends Event
  override type Data = Unit
  case object State extends RebelState
  override type Key = String

  override def keyable: RebelKeyable[Key] = implicitly
  val props = Props(new SimpleActor)

  override type State = State.type
  override implicit val dataEncoder: Encoder[Unit] = deriveEncoder
  override implicit val stateEncoder: Encoder[State] = deriveEncoder
}


object DummySharding extends ExtensionId[DummySharding] {
  override def createExtension(system: ExtendedActorSystem): DummySharding = new DummySharding(system)
}
class DummySharding(system: ExtendedActorSystem) extends RebelShardingExtension(system) {

  override type Spec = Dummy.type

  //  override val name: String = "Dummy"
  override val entryProps: Props = Dummy.props

  // disable stash
  override val syncActorImplementation: RebelEntityHostProps = (_, _) => Dummy.props

  // ignore sharding in total
  override def ask(key: String, message: RebelMessage)(implicit sender: ActorRef, timeout: Timeout): Future[Any] = {
    Future.successful(
      message match {
        case _: RebelCommand[Dummy.type] => EntityCommandSuccess(RebelDomainEvent(OnlyCommand))
        case _: TellState => CurrentState(Dummy.State, Uninitialised)
      })
  }

  override def tell(key: String, message: RebelMessage)(implicit sender: ActorRef): Unit = {
    sender.tell(EntityCommandSuccess(RebelDomainEvent(OnlyCommand)), ActorRef.noSender)
  }

  override def specClassTag: ClassTag[Dummy.Event] = classTag

  override val eventDecoder: Decoder[spec.Event] = implicitly

  override implicit def specInfo: SpecificationInfo[Dummy.type] = Dummy.specInfo
}
