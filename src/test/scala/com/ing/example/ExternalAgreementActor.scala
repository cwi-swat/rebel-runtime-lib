package com.ing.example

import akka.actor.Props
import com.ing.rebel.actors.{ExternalSpecification, RebelExternalActor}
import com.ing.rebel.specification.Specification
import com.ing.rebel.{RebelKeyable, RebelState, SpecificationEvent}
import io.circe.Encoder

object ExternalAgreement extends ExternalSpecification {
  val props = Props(new ExternalAgreementActor)

  sealed trait Command extends SpecificationEvent
  case object BookingAllowed extends Command

  sealed trait State extends RebelState
  case object AnyState extends State

  type Key = String
  override type Event = Command
  override type Data = Unit
//  override type State = Unit

  override def keyable: RebelKeyable[Key] = implicitly

  override val specificationName: String = "ExternalAgreement"
  override implicit val dataEncoder: Encoder[Data] = io.circe.generic.semiauto.deriveEncoder
  override implicit val stateEncoder: Encoder[State] = io.circe.generic.semiauto.deriveEncoder
  override val defaultState: State = AnyState
  override val defaultData: Unit = ()
}

class ExternalAgreementActor extends RebelExternalActor[ExternalAgreement.type]
