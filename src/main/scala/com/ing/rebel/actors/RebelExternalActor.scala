package com.ing.rebel.actors

import akka.actor.Actor
import cats.data.Validated.Valid
import com.ing.rebel
import com.ing.rebel.{Initialised, RebelConditionCheck}
import com.ing.rebel._
import com.ing.rebel.messages._
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.ActorRefContactPoint
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import io.circe.Encoder


trait ExternalSpecificationInfo[T <: ExternalSpecification] extends SpecificationInfo[T]

trait ExternalSpecification extends Specification { self =>
  val defaultState: this.State
  val defaultData: this.Data
  val defaultRebelData = Initialised(defaultData)

  override implicit val specInfo: ExternalSpecificationInfo[this.type] = new ExternalSpecificationInfo[this.type] {
    override def spec: self.type = self
  }
}

abstract class RebelExternalActor[S <: ExternalSpecification](implicit val externalSpecificationInfo: ExternalSpecificationInfo[S])
  extends Actor with RebelActor[S] {

  //  import scala.reflect.runtime.universe._



  /**
    * TypeTag to lets pattern matching work for type erased SpecEvent
    * http://stackoverflow.com/questions/12218641/scala-what-is-a-typetag-and-how-do-i-use-it?answertab=active#tab-top
    * TODO sometime make this safer and remove @unchecked
    */
  //  implicit val rCommandTag: scala.reflect.runtime.universe.TypeTag[SpecEvent]

  val info: SpecificationInfo[S] = implicitly

  override def receive: Receive = {
    case TellState(_) =>
      import io.circe.generic.auto._
      //      val encoder = io.circe.generic.semiauto.deriveEncoder[CurrentState]
      sender() ! CurrentState(externalSpecificationInfo.spec.defaultState, Initialised(externalSpecificationInfo.spec.defaultData))
    case cp: CheckPreconditions[S] =>
      sender() ! CheckPreconditionsResult(cp.operation.transactionId, Valid(Set()))
      log.info("Received [{}] -> result true", cp)
    //    case IsInitialized                                      =>
    //      sender() ! CheckPreconditionsResult(RebelConditionCheck.success)
    case event: RebelCommand[S] =>
      // always succeed for external specification
      sender() ! EntityCommandSuccess(event.domainEvent)
//    case GetSyncInitialize(event, _) =>
//      val syncCommand: SyncSpecEvent = SyncAction(
//        ParticipantId(UniqueId(event.transactionId.transactionId + "p")),
//        ActorRefContactPoint(context.parent),
//        event)
//      sender() ! Initialize(event.transactionId, Set(syncCommand))
  }

  override def unhandled(message: Any): Unit = log.error("Unhandled message in RebelExternalActor {}", message)

  //
  //  implicit val currentStateEncoder: Encoder[CurrentState.CurrentStateInternal[ExternalState.type, String]] =
  //    io.circe.generic.semiauto.deriveEncoder

}