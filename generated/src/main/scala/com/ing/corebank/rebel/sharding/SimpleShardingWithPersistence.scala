package com.ing.corebank.rebel.sharding

import akka.actor.{ExtendedActorSystem, ExtensionId, Props}
import akka.persistence.PersistentActor
import com.ing.corebank.rebel.sharding.SimpleWithPersistence.OnlyCommand
import com.ing.rebel.RebelSharding.{RebelEntityHostProps, RebelShardingExtension}
import com.ing.rebel._
import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.messages.EntityCommandSuccess
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import io.circe.generic.auto._
import io.circe.generic.semiauto.deriveEncoder
import io.circe.{Decoder, Encoder}

import scala.reflect.ClassTag

object SimpleWithPersistence extends Specification {
  sealed trait Event extends SpecificationEvent with SerializeWithKryo
  case object OnlyCommand extends Event

  val props = Props(new SimpleActorWithPersistence)
  override type Data = Unit
  case object State extends RebelState
  override type Key = String

  override def keyable: RebelKeyable[Key] = implicitly

  override type State = State.type
  override implicit val dataEncoder: Encoder[Unit] = deriveEncoder
  override implicit val stateEncoder: Encoder[State] = deriveEncoder
}
class SimpleActorWithPersistence extends PersistentActor {

  override def receiveRecover: Receive = {case _ => }

  override def receiveCommand: Receive = {
    case _ =>
      persist(OnlyCommand) { e =>
        sender ! EntityCommandSuccess(RebelDomainEvent(e))
      }
  }

  override val persistenceId: String = self.path.toStringWithoutAddress
}


object SimpleShardingWithPersistence extends ExtensionId[SimpleShardingWithPersistence] {
  override def createExtension(system: ExtendedActorSystem): SimpleShardingWithPersistence = new SimpleShardingWithPersistence(system)
}
class SimpleShardingWithPersistence(system: ExtendedActorSystem) extends RebelShardingExtension(system) {
  override val name: String = "SimpleWithPersistence"
  override val entryProps: Props = SimpleWithPersistence.props

  override val syncActorImplementation: RebelEntityHostProps = (_,_) => SimpleWithPersistence.props
 override type Spec = SimpleWithPersistence.type

  override def specClassTag: ClassTag[SimpleWithPersistence.Event] = implicitly[ClassTag[SimpleWithPersistence.Event]]

  override val eventDecoder: Decoder[spec.Event] = implicitly

  override implicit def specInfo: SpecificationInfo[SimpleWithPersistence.type] = SimpleWithPersistence.specInfo
}
