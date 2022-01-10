package com.ing.rebel

import akka.actor.{Actor, ActorRef, ActorSystem, Extension, Props}
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion.{EntityId, Msg, ShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.ing.rebel.RebelSharding.ShardingEnvelope
import com.ing.rebel.messages.{CurrentState, RebelCommand, RebelMessage, TellState}
import com.ing.rebel.actors.{ExternalSpecification, ExternalSpecificationInfo, RebelExternalActor}
import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import com.ing.rebel.sync.RebelSync
import com.ing.rebel.sync.twophasecommit.EntityHost
import io.circe.{Decoder, Encoder}
import kamon.Kamon
import kamon.metric.Metric

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object RebelSharding {

  case class ShardingEnvelope(RebelId: Any, message: RebelMessage) extends SerializeWithKryo

  val defaultIdExtractor: ShardRegion.ExtractEntityId = {
    case ShardingEnvelope(key, payload) => (key.toString, payload)

  }

  def defaultShardResolver(shardCount: Int): ShardRegion.ExtractShardId = {
    case ShardingEnvelope(key, _) => (math.abs(key.hashCode) % shardCount).toString
    case ShardRegion.StartEntity(id) =>
      // StartEntity is used by remembering entities feature
      (id.toLong % shardCount).toString
  }

  // 10 * max number of nodes
  val shardCount: Int = 100

  abstract class RebelShardingProxy(system: ActorSystem) {
    type Spec <: Specification
    implicit def specInfo: SpecificationInfo[Spec]

    val spec: Spec = specInfo.spec
    val name: String = spec.specificationName
    val keyable: RebelKeyable[spec.Key] = spec.keyable

//    implicit val ev: Spec <:< spec.type
//    implicit val ev2: spec.type <:< Spec
//    implicit val stateEncoder : Encoder[CurrentState[Spec]] = spec.currentStateEncoder

    //noinspection ScalaStyle
    def !(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef = ActorRef.noSender): Unit =
      tell(key, message)

    //noinspection ScalaStyle
    def ?(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef = ActorRef.noSender, timeout: akka.util.Timeout): Future[Any] =
      ask(key, message)

    def tell(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef = ActorRef.noSender): Unit =
      shardRegion.tell(ShardingEnvelope(key, message), sender)

    def ask(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef = ActorRef.noSender, timeout: akka.util.Timeout): Future[Any] =
      akka.pattern.ask(shardRegion, ShardingEnvelope(key, message))

    // utils
    // Don't use this for now, because generator also only uses direct `IsInitialized` messages
    //    def isInitialized(key: Key)(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Boolean] = {
    //      ask(key, IsInitialized).mapTo[IsInitializedResult].map(_.initialized)
    //      ask(key, IsInitialized).mapTo[RebelConditionCheck].map(_.isValid)
    //    }
    // Don't use this for now, because generator also only uses direct messages
    //    def inState(key: Key, rebelState: RebelState)(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Boolean] = {
    //      ask(key, InState(rebelState)).mapTo[InStateResult].map(_.result)
    //    }

    def startProxy(): Unit = {
      system.log.debug("Starting sharding proxy for `{}` on `{}`", name, Cluster(system).selfAddress)
      ClusterSharding(system).startProxy(
        typeName = name,
        // TODO ? Add use roles?
        role = None,
        extractEntityId = defaultIdExtractor,
        extractShardId = defaultShardResolver(shardCount)
      )
    }

    protected def shardRegion: ActorRef =
      ClusterSharding(system).shardRegion(name)
  }


  /**
    * Props that can start the specification actor
    */
  type RebelActorInitializer = Props
  /**
    * Props which need a props to instantiate as child to proxy to
    * String = Name of the actor
    */
  type RebelEntityHostProps = (RebelActorInitializer, String) => Props

  abstract class RebelShardingExtension(system: ActorSystem)
    extends RebelShardingProxy(system) with Extension {

    // Compiler says YES
//    implicit val eventIsEvent : Spec#Event =:= spec.Event = implicitly[Spec#Event =:= spec.Event]

    def specClassTag: ClassTag[Spec#Event]
    val commandEndpoints: RebelCommandRestEndpoint[Spec] = new RebelCommandRestEndpoint[Spec]()(specClassTag)

    val eventDecoder : Decoder[spec.Event]

    val entryProps: RebelActorInitializer

    // TODO should only return entities that are not uninitialised and not finalised
    def allActiveEntityIds(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Set[EntityId]] = {
      // TODO make it collect all ids from all shards
      // TODO make it also collect entity id's that are passivated
      // probably use a cluster singleton to keep track of the relevant entityids
      //      akka.pattern.ask(shardRegion, ShardRegion.GetShardRegionState)
      //        .mapTo[ShardRegion.CurrentShardRegionState].map(_.shards.flatMap(_.entityIds))
      implicit val actorSystem: ActorSystem = system
      implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()

      // hacky, all ids
      // TODO optimise this instead of getting all items
      // TODO intialised, finalised?
      val readJournal: CassandraReadJournal =
      PersistenceQuery(system).readJournalFor[CassandraReadJournal](
        CassandraReadJournal.Identifier)

      // only existing persistent ids
      readJournal.currentPersistenceIds()
        // we know that all relevant specification actors end with the type name
        .filter(_.endsWith(name))
        // we know that for now the id is always the 6th part of the path
        .map(_.split('/')(5))
        .runFold(Set[EntityId]())(_ + _)
    }

    def allActiveEntities(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Set[(EntityId, CurrentState[spec.type])]] = {
      allActiveEntityIds.flatMap(ids => {
        val eventualStates: Set[Future[(EntityId, CurrentState[spec.type])]] = ids.map { id =>
          system.log.debug("Asking state for {}", id)
          val key: spec.Key = this.keyable.stringToKey(id).get
          ask(key, TellState()).mapTo[CurrentState[spec.type]].map(state => id -> state)
        }
        Future.sequence(eventualStates)
      })
    }

    protected val syncActorImplementation: RebelEntityHostProps =
      (entryProps, name) => entryProps.withMailbox("rebel.stash-capacity-mailbox")

    val shardShardIdLookupCounter: Metric.Counter = Kamon.counter(s"shardid-$name")

    def start(): Unit = {
      system.log.debug("Starting sharding host for `{}` on `{}`", name, Cluster(system).selfAddress)
      ClusterSharding(system).start(
        typeName = name,
        entityProps = syncActorImplementation(entryProps, name),
        settings = ClusterShardingSettings(system),
        extractEntityId = defaultIdExtractor,
        extractShardId = { m: Msg =>
          val result: ShardId = defaultShardResolver(shardCount)(m)
          val counter = m match {
            case ShardingEnvelope(key, RebelCommand(domainEvent))  =>
              shardShardIdLookupCounter.withTag("shardId", result).withTag("msgType", domainEvent.specEvent.getClass.getSimpleName)
            case ShardingEnvelope(key, mess) =>
              shardShardIdLookupCounter.withTag("shardId", result).withTag("msgType", mess.getClass.getSimpleName)
            case _                           => shardShardIdLookupCounter.withTag("shardId", result)
          }
          counter.increment()
          result
        }
      )
    }
  }

  abstract class RebelExternalShardingExtension(system: ActorSystem)
    extends RebelShardingExtension(system) {

    type Spec <: ExternalSpecification

    implicit override def specInfo: ExternalSpecificationInfo[Spec]

    val responseActor: ActorRef = system.actorOf(Props(new RebelExternalActor[Spec] {}))

    override def start(): Unit = ()

    override def tell(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef): Unit = {
      responseActor ! message
    }

    override def ask(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef, timeout: Timeout): Future[Any] = {
      akka.pattern.ask(responseActor, message)
    }

    override val syncActorImplementation: RebelEntityHostProps = (_,_) =>
      throw new NotImplementedError("syncActorImplementation should not be called on RebelExternalShardingExtension")
  }

}


