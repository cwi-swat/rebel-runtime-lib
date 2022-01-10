package akka.contrib

import akka.actor.Actor

trait MessageInterceptor extends Actor {

  def onIncomingMessage(msg: Any): Unit

  override protected[akka] def aroundReceive(receive: Receive, msg: Any): Unit = {
    onIncomingMessage(msg)
    super.aroundReceive(receive, msg)
  }

}
