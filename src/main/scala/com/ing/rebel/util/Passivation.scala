package com.ing.rebel.util

import akka.actor.{Actor, ActorLogging, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.HandledCompletely
import com.ing.rebel._
import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.util.Passivation.StopMessage

import scala.concurrent.duration.FiniteDuration


object Passivation {
  case object StopMessage extends SerializeWithKryo
}

trait Passivation extends Actor with ReceivePipeline {
  this: ActorLogging =>
  // automatic passivation
  def passivationTimeout: FiniteDuration = this.context.system.settings.config.getDuration("rebel.passivation-timeout").asScala

  override def preStart(): Unit = {
    log.debug("Setting passivation duration to {}", passivationTimeout)
    context.setReceiveTimeout(passivationTimeout)
    super.preStart()
  }

  //  override def receive: Receive = withPassivation(super.receive)
  pipelineOuter {
    // tell parent actor to send us a poison pill
    case ReceiveTimeout =>
      log.debug("ReceiveTimeout: passivating")
      //      context.actorSelection(self.path.parent.parent) ! Passivate(stopMessage = PoisonPill)
      //      Shard()
      context.parent ! Passivate(stopMessage = StopMessage)
      HandledCompletely
    // stop
    case StopMessage =>
      context.stop(self)
      HandledCompletely
  }
}

/**
  * Forward (tell) passivation event to parent shard. This class should be mixed in every class between the Rebel actors and the shard regions
  * TODO: Should it also gracefully stop children to reduce message lostness?
  */
trait PassivationForwarder extends ReceivePipeline {
  this: ActorLogging =>
  pipelineOuter {
    case p@Passivate(_) =>
      context.parent ! p
      HandledCompletely
    case StopMessage =>
      log.debug("StopMessage (Passivating) received: Stopping")
      context.stop(self)
      HandledCompletely
  }
}