/*
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package com.ing.util

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.persistence.PersistentActor
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.testkit.TestProbe
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest._

import scala.concurrent.duration._

object CassandraLifecycle {

//  val config: Config = ConfigFactory.parseString(
//    """
//    akka.persistence.journal.plugin = "cassandra-journal"
//    akka.persistence.snapshot-store.plugin = "cassandra-snapshot-store"
//    cassandra-journal.circuit-breaker.call-timeout = 30s
//    akka.test.single-expect-default = 20s
//    akka.actor.serialize-messages=on
//    """)

  def awaitPersistenceInit(system: ActorSystem): Unit = {
    system.log.info("starting awaitPersistenceInit")
    val probe = TestProbe()(system)
    val t0 = System.nanoTime()
    var n = 0
    probe.within(45.seconds) {
      probe.awaitAssert {
        n += 1
        system.actorOf(Props[AwaitPersistenceInit], "persistenceInit" + n).tell("hello", probe.ref)
        probe.expectMsg(5.seconds, "hello")
        system.log.info("awaitPersistenceInit took {} ms {}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0), system.name)
      }
    }

  }

  class AwaitPersistenceInit extends PersistentActor {
    val persistenceId: String = "persistenceInit"

    def receiveRecover: Receive = {
      case _ =>
    }

    def receiveCommand: Receive = {
      case msg =>
        persist(msg) { _ =>
          sender() ! msg
          context.stop(self)
        }
    }
  }
}

trait CassandraStarter {
  def system: ActorSystem

  def systemName: String = system.name

  def cassandraConfigResource: String = CassandraLauncher.DefaultTestConfigResource

  val cassandraPort: Int = CassandraLauncher.randomPort

  def startCassandra(): Unit = {
    system.log.info("Starting Cassandra on localhost:{}", cassandraPort)
    val cassandraDirectory = new File(s"target/cassandra/$systemName")
    CassandraLauncher.start(
      cassandraDirectory,
      configResource = cassandraConfigResource,
      clean = true,
      port = 0
    )
    system.log.info("Started Cassandra on localhost:{}", cassandraPort)
    awaitPersistenceInit()
  }

  def awaitPersistenceInit(): Unit = {
    CassandraLifecycle.awaitPersistenceInit(system)
  }
}

trait CassandraLifecycle extends CassandraStarter with BeforeAndAfterAll {
  this: Suite =>

  override def beforeAll(): Unit = {
    startCassandra()
    super.beforeAll()
  }

}