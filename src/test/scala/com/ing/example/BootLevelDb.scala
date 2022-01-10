package com.ing.example

import akka.actor.{ActorIdentity, ActorPath, Identify, Props}
import akka.cluster.Cluster
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import com.ing.example.sharding.{AccountSharding, TransactionSharding}
import com.ing.rebel.RebelRestEndPoints.RebelEndpointInfo
import com.ing.rebel.boot.BootLib

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import BootLib._
import akka.util.Timeout

object BootLevelDb extends App with BootLib {
  override def endpointInfos: Set[RebelEndpointInfo] =
    Set(RebelEndpointInfo(TransactionSharding(system)),
        RebelEndpointInfo(AccountSharding(system)))

  applySystemProperties(argsToOpts(this.args.toSeq :+ "-Dconfig.resource=/leveldb.conf"))

  start()

  // setup shared level db
  val firstSeed: ActorPath = ActorPath.fromString(system.settings.config.getStringList("akka.cluster.seed-nodes").asScala.headOption.getOrElse(Cluster(system).selfAddress.toString))
  system.log.info(s"Using for levelDB: $firstSeed")

  val thisPort: Int = system.settings.config getInt "akka.remote.netty.tcp.port"
  println(s"Port from config: $thisPort")

  // if seed node
  private val isFirstSeedNode: Boolean = thisPort == firstSeed.address.port.get
  if (isFirstSeedNode) {
    // start shared level db
    val store = system.actorOf(Props[SharedLeveldbStore], "store")
    system.log.info(s"Started SharedLeveldbStore on this seednode at ${store.path}")
  }

  import akka.pattern.ask
  implicit val timeout: Timeout = Timeout(10.seconds)

  val askSharedLevelDbNode: Future[ActorIdentity] = (system.actorSelection(firstSeed / "user" / "store") ? Identify(1)).mapTo[ActorIdentity]
  Await.result(askSharedLevelDbNode, 3.seconds) match {
    case ActorIdentity(1, Some(store)) =>
      system.log.info(s"Setting SharedLeveldbStore to ${store.path}")
      SharedLeveldbJournal.setStore(store, system)
  }


}