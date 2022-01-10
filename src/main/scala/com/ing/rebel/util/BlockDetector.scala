package com.ing.rebel.util

import akka.actor.ActorLogging
import akka.contrib.pattern.ReceivePipeline
import akka.contrib.pattern.ReceivePipeline.HandledCompletely
import com.ing.rebel.util.MessageLogger.{CommentEvent, MessageEvent}

import scala.concurrent.duration._

/**
  * Simple block detector. Mix in to have ticks send to you actor. If the log streams stops, it means your actor is blocked
  */
trait BlockDetector {
  this : ActorLogging with ReceivePipeline with LoggingInterceptor =>

  import context.dispatcher

//  private val logger = context.actorOf(Props(new Actor with ActorLogging {
//    override def receive: Receive = {
//      case m => log.info(s"Received: $m")
//    }
//  }))

  case object Tick
  // debug
  context.system.scheduler.schedule(0.seconds, 100.milliseconds, self, Tick)
  pipelineOuter {
    case Tick =>
      this.publishMessageEvent(CommentEvent(self, "Tick"))
      log.info("still receiving")
      HandledCompletely
  }

}
