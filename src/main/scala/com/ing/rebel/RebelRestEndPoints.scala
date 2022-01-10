package com.ing.rebel

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.cluster.sharding.ShardRegion.EntityId
import akka.http.javadsl.model.headers.AccessControlAllowMethods
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.HttpMethods.{GET, OPTIONS, POST}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{PathMatcher1, Route, StandardRoute}
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.Flow
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.Timeout
import com.ing.rebel.RebelRestEndPoints.RebelEndpointInfo
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel.config.{RebelConfig, RebelConfigExtension}
import com.ing.rebel.messages._
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.typesafe.config.ConfigFactory
import io.circe.{Decoder, Json}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.ClassTag
import scala.util.Try

object RebelRestEndPoints {
  // to only way to get an instance is through the type safe apply method in the companion object
  abstract case class RebelEndpointInfo(specificationShard: RebelShardingExtension) {
    // Internalise key, so it is possible to reference it outside.
    // _ in argument is op type Key
//    type Key
    type Spec = specificationShard.Spec
  }
  object RebelEndpointInfo {
    private class RebelEndpointInfoInternal[S <: Specification]
    (override val specificationShard: RebelShardingExtension)
      extends RebelEndpointInfo(specificationShard) {
//      override type Key = T
    }
    //     This factory makes sure only compatible keys are constructed
    def apply[S <: Specification](specificationShard: RebelShardingExtension): RebelEndpointInfo =
      new RebelEndpointInfoInternal(specificationShard)
  }
}

class RebelRestEndPoints(actorSystem: ActorSystem, endpointInfos: Set[RebelEndpointInfo]) {
  implicit val queryTimeout: Timeout = Timeout(RebelConfig(actorSystem).rebelConfig.endpoints.queryTimeout)

  implicit val system: ActorSystem = actorSystem
  implicit val materializer: ActorMaterializer =
    if (RebelConfig(actorSystem).rebelConfig.endpoints.blockingDispatcher) {
      ActorMaterializer(ActorMaterializerSettings(system).withDispatcher("akka-http-dispatcher"))
    } else {
      ActorMaterializer()
    }
  implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher

  import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

  // Comment this in if we do not want to print nulls
  //  implicit val printer: Printer = Printer.noSpaces.copy(dropNullKeys = true)

  val rebelEndpoints: Route = {
    val routesAndUrls: Seq[(Route, Seq[String])] = endpointInfos.toSeq.map {
      endpointInfo: RebelEndpointInfo =>
        endpointInfo.specificationShard match {
          // pattern match/cast => we know `Key`s are the correct `Key`s because of constructor for `RebelEndpointInfo`
          case specSharding: RebelShardingExtension =>
            val commandRestEndpoints: RebelCommandRestEndpoint[specSharding.Spec] = specSharding.commandEndpoints

            val keyType = specSharding.spec.keyable.keyClassTag.runtimeClass.getSimpleName

            val endpointUrls = Seq(
              s"GET  /${specSharding.name}",
              s"GET  /${specSharding.name}/:id($keyType)"
            ) ++ commandRestEndpoints.commandNames.map { commandName =>
              s"POST /${specSharding.name}/:id/$commandName"
            }

            system.log.info("registering endpoints: {}", endpointUrls.mkString("\n"))
//            val spec: specSharding.Spec = specSharding.spec
            val keyable = specSharding.keyable
            val keyUnmarshaller = keyable.pm

            val route = pathPrefix(specSharding.name) {
              val route1: Route = pathEndOrSingleSlash {
                parameter(Symbol("withEntityBody").?) {
                  withEntityBody : Option[String] =>
                    withEntityBody.fold {
                      complete(specSharding.allActiveEntityIds)
                    } {
                      // TODO make use of info on commands, to use circe for json
                      _ =>
                        complete {
                          import io.circe.generic.auto._
                          import specSharding.spec._
                          import specSharding._
                          val eventualMap: Future[Map[EntityId, CurrentState[specSharding.spec.type]]] = specSharding.allActiveEntities.map(_.toMap)
                          eventualMap.map(_.mapValues(specSharding.spec.currentStateEncoder.apply).toMap)
                          //                        ToResponseMarshallable.apply(eventualMap)
                        }
                    }
                }
              }
              route1 ~
                pathPrefix(keyUnmarshaller) { key =>
                  pathEndOrSingleSlash {
                    options {
                      complete {
                        HttpResponse(StatusCodes.OK).withHeaders(AccessControlAllowMethods.create(GET))
                      }
                    } ~
                      get {
                        complete {
                          import io.circe.generic.auto._
                          import specSharding.spec._
                          import specSharding._
                          val futureState = specSharding ? (key, TellState())
                          if(system.log.isDebugEnabled) {
                            futureState.foreach { s => system.log.debug("Got {} of type {}", s, s.getClass) }
                          }
                          val state: Future[CurrentState[specSharding.spec.type]] = futureState.mapTo[CurrentState[specSharding.spec.type]]
                          state.map(cs => specSharding.spec.currentStateEncoder.apply(cs))
                        }
                      }
                  } ~ {
                    commandRestEndpoints.commandRoutes(specSharding)(key)
                  }
                }
            }
            (route, endpointUrls)
          case unknown                                                      =>
            system.log.error("Incorrectly typed `RebelEndpointInfo`. This should not happen: `{}`", unknown)
            throw new IllegalArgumentException(s"Incorrectly typed `RebelEndpointInfo`. This should not happen: `$unknown`")
        }
    }

    val (routes, endpointUrls) = routesAndUrls.unzip
    val urlsRoute = pathEndOrSingleSlash {
      get {
        complete(endpointUrls.flatten: Seq[String])
      }
    }
    routes.foldRight[Route](urlsRoute)(_ ~ _)

  }

  import ch.megard.akka.http.cors.scaladsl.CorsDirectives._

  val route: Route = cors() {
    rebelEndpoints
  }

  val flow: Flow[HttpRequest, HttpResponse, NotUsed] = route
}

/**
  * A way to parse a string to a Key
  *
  * @tparam Key Type of Rebel Key
  */
abstract class RebelKeyable[Key](implicit val keyClassTag: ClassTag[Key]) {
  def stringToKey: String => Option[Key]

  implicit def pm: PathMatcher1[Key] = Segment.flatMap(stringToKey)
}

object RebelKeyable extends LessPrio {
  def apply[T](fun: String => Option[T])(implicit kTag: ClassTag[T]): RebelKeyable[T] = new RebelKeyable[T] {
    override def stringToKey: String => Option[T] = fun
  }

  implicit val uuid: RebelKeyable[UUID] = new RebelKeyable[UUID] {
    override def stringToKey: String => Option[UUID] = s => Try(UUID.fromString(s)).toOption

    override def pm: PathMatcher1[UUID] = JavaUUID
  }

  implicit val int: RebelKeyable[Int] = new RebelKeyable[Int] {
    override def stringToKey: String => Option[Int] = s => Try(s.toInt).toOption

    // specialisation
    override implicit def pm: PathMatcher1[Int] = IntNumber
  }

}

trait LessPrio {
  implicit val string: RebelKeyable[String] = new RebelKeyable[String]() {
    override def stringToKey: String => Option[String] = s => Some(s)

    override implicit def pm: PathMatcher1[String] = Segment
  }
}


class RebelCommandRestEndpoint[S <: Specification](implicit ct: ClassTag[S#Event]) {

//  type Key = S#Key

  import language.experimental.macros
  import scala.reflect.runtime.universe._
  import scala.reflect.runtime.{currentMirror => cm}

  val commands: Set[Symbol] = cm.classSymbol(ct.runtimeClass).knownDirectSubclasses
  val commandNames: Set[String] = commands.map(_.name.decodedName.toString)

  implicit val commandTimeout: Timeout = Timeout(new RebelConfigExtension(ConfigFactory.load()).rebelConfig.endpoints.commandTimeout)

  def commandRoutes(specSharding: RebelShardingExtension)(key: specSharding.spec.Key)(implicit executionContext: ExecutionContext): Route = {
    val commandNotFoundRoute: StandardRoute = complete(HttpResponse(status = StatusCodes.NotFound, entity = s"Event not found for `${specSharding.name}`"))
    commandNames.map(commandName => commandRoute(specSharding, commandName)(key)
    ).foldRight[Route](commandNotFoundRoute)(_ ~ _)
  }

  def commandRoute(specSharding: RebelShardingExtension, commandName: String)(key: specSharding.spec.Key)
                  (implicit executionContext: ExecutionContext): Route = {
    import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
    implicit val decoder: Decoder[specSharding.spec.Event] = specSharding.eventDecoder
    pathPrefix(commandName) {
      pathEndOrSingleSlash {
        options {
          complete {
            HttpResponse(StatusCodes.OK).withHeaders(AccessControlAllowMethods.create(GET, POST, OPTIONS))
          }
        }
      } ~
        post {
          pathEndOrSingleSlash {
            entity(as[specSharding.spec.Event]) { specEvent: specSharding.spec.Event => // does only work for sealed traits
              complete {
                val dEvent: specSharding.spec.RDomainEvent = RebelDomainEvent[specSharding.spec.type](specEvent)
                val command = RebelCommand(dEvent)
                (specSharding ? (key, command)).map {
                  case success@EntityCommandSuccess(_) => HttpResponse(entity = success.toString)
                  case failed@EntityCommandFailed(_, _)   => HttpResponse(status = StatusCodes.BadRequest, entity = failed.toString)
                  case EntityTooBusy             => HttpResponse(status = StatusCodes.TooManyRequests, entity = "Resource is too busy. SpecEvent canceled.")
                }.recover {
                  case ate: AskTimeoutException => HttpResponse(
                    // Should be StatusCodes.Accepted, since probably accepted, but not yet completed
                    status = StatusCodes.EnhanceYourCalm,
                    entity = s"Unable to serve response within time limit, please enhance your calm. " +
                      s"Timeout $commandTimeout reached, command could still be executed. ${ate.getMessage}")
                }
              }
            }
          }
        }
    }
  }
}
