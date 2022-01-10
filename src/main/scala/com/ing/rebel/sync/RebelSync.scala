package com.ing.rebel.sync

import akka.actor.ActorPath
import com.datastax.driver.core.utils.UUIDs


object RebelSync {
  type UniqueId = String
  object UniqueId {
    //    @volatile var count : AtomicInteger = new AtomicInteger(0)
    def apply(startWith: String = ""): UniqueId = {
      //      this.synchronized {
      //        s"${startWith}_${count.incrementAndGet()}"
      //      }
      safeActorName(s"$startWith${UUIDs.timeBased()}")
    }

    // TODO is this enough?
    def safeActorName(lockId: UniqueId): String = {
      val actorName = lockId.replace('/', '-')
//      require(ActorPath.isValidPathElement(actorName))
      actorName
    }
  }
}
