package com.ing.util

import akka.actor.{ActorRef, ActorSystem, Cancellable}
import akka.testkit
import akka.testkit.{ImplicitSender, TestActor, TestKit, TestKitBase}
import akka.testkit.TestActor.AutoPilot
import com.ing.rebel.util.MessageLogger.{CommentEvent, MessageEvent}

import scala.concurrent.duration._

trait MessageLoggerAutoPilot {
  this: TestKitBase with ImplicitSender =>

  val messageLoggerPilot: AutoPilot = new testkit.TestActor.AutoPilot {
    override def run(sender: ActorRef, msg: Any): AutoPilot = {
      system.eventStream.publish(MessageEvent(sender, self, msg.toString))
      this
    }
  }
  setAutoPilot(messageLoggerPilot)

  def addTimer()(implicit system: ActorSystem): Cancellable = {
    var duration: FiniteDuration = 0.millis
    import system.dispatcher
    system.scheduler.schedule(0.seconds, 100.milliseconds) {
      system.eventStream.publish(CommentEvent("timer",
        duration.toString().replaceAll(" milliseconds", "ms")))
      duration += 100.millis
    }
  }
}