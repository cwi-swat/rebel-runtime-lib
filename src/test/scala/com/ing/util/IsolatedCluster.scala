package com.ing.util

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.Cluster
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import com.ing.util.LevelDbClusterTestKit.deletePersistenceFiles
import com.typesafe.config.{Config, ConfigFactory, ConfigMergeable}
import org.iq80.leveldb.util.FileUtils
import org.scalatest._

import scala.util.Random

abstract class IsolatedCluster(extraConfig : Config = ConfigFactory.empty)
  extends TestKit(TestActorSystemManager.createSystemWithInMemPersistence(extraConfig))
    with FlatSpecLike with BeforeAndAfterEach with DefaultTimeout
    with BeforeAndAfterAll with ImplicitSender with OneInstancePerTest {

  override protected def afterAll(): Unit = {
    system.log.debug("SHUTDOWN ACTORSYSTEM!")
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  // Make sure the actor system in each parallel test is shut down correctly
  override def afterEach(): Unit = {
    system.log.debug("SHUTDOWN ACTORSYSTEM! (afterEach)")
    TestKit.shutdownActorSystem(system)
    super.afterEach()
  }
}

abstract class CassandraClusterTestKit extends TestKit(TestActorSystemManager.createSystemWithCassandra())
  with FlatSpecLike with BeforeAndAfterEach with DefaultTimeout
  with BeforeAndAfterAll with ImplicitSender with OneInstancePerTest {

  override def afterAll(): Unit = {
    system.log.debug("SHUTDOWN ACTORSYSTEM!")
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  // Make sure the actor system in each parallel test is shut down correctly
  override def afterEach(): Unit = {
    system.log.debug("SHUTDOWN ACTORSYSTEM! (afterEach)")
    TestKit.shutdownActorSystem(system)
    super.afterEach()
  }
}

object LevelDbClusterTestKit {
  def storageLocations(system: ActorSystem): List[File] = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s => new File(system.settings.config.getString(s)))


  def deletePersistenceFiles(system: ActorSystem): Unit = {
    storageLocations(system).foreach(dir => FileUtils.deleteRecursively(dir))
  }
}

object TestActorSystemManager {
  private val randomNameLength = 5

  def createSystemWithInMemPersistence(extraConfig: Config): ActorSystem = {
    val inMemConfig = ConfigFactory.parseString(
      """ akka.persistence {
        |   journal.plugin = "akka.persistence.journal.inmem"
        |   snapshot-store {
        |      plugin = "akka.persistence.snapshot-store.local"
        |      auto-start-snapshot-stores = ["akka.persistence.snapshot-store.local"]
        |    }
        | }
      """.stripMargin)
    // use level db to make sure persistence snapshots are correct
    createSystemWithLevelDb(true, false, inMemConfig.withFallback(extraConfig))
  }

  def createSystemWithLevelDb(tempPersistence: Boolean, sharedLevelDb: Boolean, extraConfig: Config): ActorSystem = {
    val pathPrefix = "test" + (if (tempPersistence) Random.alphanumeric.take(5).mkString else "")
    val config: Config = createLevelDbConfig(sharedLevelDb, pathPrefix, extraConfig)
    val system = ActorSystem(s"IsolatedCluster_$pathPrefix", config.resolve())
    if (sharedLevelDb) {
      setupSharedLevelDb(system)
    }
    setupSharding(system)
    system.registerOnTermination {
      deletePersistenceFiles(system)
    }
    system.log.info("Started new ActorSystem ({}) with level db {}", system.name, config.toString)
    system
  }

  def createLevelDbConfig(sharedLevelDb: Boolean, pathPrefix: String, extraConfig: Config): Config = {
    val levelDbConfigName = if (sharedLevelDb) "leveldb-shared" else "leveldb"
    val levelDbConfig: Config = ConfigFactory.parseString(
      s"""akka {
         |  persistence {
         |       journal {
         |         plugin = akka.persistence.journal.$levelDbConfigName
         |         leveldb {
         |           dir = "target/leveldb/$pathPrefix-journal"
         |         }
         |         leveldb-shared {
         |           dir = "target/leveldb/$pathPrefix-journal"
         |         }
         |         // needed to prevent link error
         |         leveldb.native = off
         |         // this is only for shared
         |         leveldb-shared.store.native = off
         |       }
         |       snapshot-store {
         |         leveldb.native = off
         |         plugin = "akka.persistence.snapshot-store.local"
         |         local.dir = "target/leveldb/$pathPrefix-snapshots"
         |       }
         |   }
         |}
      """.stripMargin)
    extraConfig.withFallback(levelDbConfig).withFallback(defaultShardingConfig)
  }

  def createSystemWithCassandra(actorSystemName: String = Random.alphanumeric.take(randomNameLength).mkString,
                                cassandraPort: Int = CassandraLauncher.randomPort,
                                extraConfig: Config = ConfigFactory.empty()): ActorSystem = {
    val cassandraConfig = createCassandraConfig(actorSystemName, cassandraPort)
    val config = extraConfig.withFallback(cassandraConfig).withFallback(defaultShardingConfig)

    val system = ActorSystem(actorSystemName, config.resolve())
    system.log.info("Starting new ActorSystem ({}) with Cassandra on {}", system.name, cassandraPort)
    setupSharding(system)
    system
  }

  def defaultShardingConfig: Config = ConfigFactory.parseString(
    s"""
      |include "application.conf"
      |akka {
      |  loglevel = "DEBUG"
//    |  cluster.metrics.enabled=off
      |  actor.provider = "akka.cluster.ClusterActorRefProvider"
      |  actor {
//    |      serialize-messages = on
//    |      serialize-creators = on
      |  }
      |  remote {
      |    log-remote-lifecycle-events = off
      |    netty.tcp {
      |      hostname = "localhost"
      |      port = 0
      |    }
      |  }
      |  cluster.jmx.multi-mbeans-in-same-jvm = on
      |}
    """.stripMargin)


  def setupSharedLevelDb(system: ActorSystem): Unit = {
    val levelDbStore: ActorRef = system.actorOf(Props[SharedLeveldbStore], "store")
    SharedLeveldbJournal.setStore(levelDbStore, system)
  }

  def setupSharding(system: ActorSystem): Unit = {
    // Join self
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
  }

  def createCassandraConfig(actorSystemName: String, cassandraPort: Int): Config = {
    ConfigFactory.parseString(
      s"""
         |akka {
         |  persistence {
         |    journal {
         |      plugin = "cassandra-journal"
         |    }
         |    snapshot-store {
         |      plugin = "cassandra-snapshot-store"
         |    }
         |  }
         |}
         |cassandra-journal {
         |  contact-points = ["127.0.0.1"]
         |  port = $cassandraPort
         |  keyspace = "akka_$actorSystemName"
//         |  keyspace-autocreate = true
//         |  tables-autocreate = true
         |}
         |cassandra-snapshot-store {
         |  port=$cassandraPort
         |  keyspace = "akka_snapshot_$actorSystemName"
//         |  keyspace-autocreate = true
//         |  tables-autocreate = true
         |}
    """.stripMargin)
  }
}
