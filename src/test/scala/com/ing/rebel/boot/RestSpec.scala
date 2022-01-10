package com.ing.rebel.boot

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardRegion.EntityId
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.util.Timeout
import com.github.nscala_time.time.Imports._
import com.ing.example.ExternalAgreement.{BookingAllowed, Command}
import com.ing.example.{ExternalAgreement, ExternalAgreementActor, Transaction}
import com.ing.rebel.util.CirceSupport._
import com.ing.rebel.RebelRestEndPoints.RebelEndpointInfo
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel.actors.RebelExternalActor
import com.ing.rebel.messages._
import com.ing.rebel.{Iban, RebelCommandRestEndpoint, RebelKeyable, RebelRestEndPoints, _}
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import io.circe.generic.auto._
import io.circe.{Decoder, Encoder, Json}
import org.scalatest.{FlatSpec, Matchers, OneInstancePerTest}
import squants.market.{EUR, Money}
import RestSpec.{Dummy, DummyIntKey, _}
import cats.scalatest.EitherMatchers
import com.github.nscala_time.time.DurationBuilder
import com.ing.example.ExternalAgreement.BookingAllowed
import com.ing.example.sharding.{AccountSharding, ExternalAgreementSharding}
import com.ing.rebel.boot.RestSpec.DummyIntKey.{RData, State}
import com.ing.rebel.specification.{Specification, SpecificationInfo}
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.util.TestActorSystemManager

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import io.circe.generic.auto._

import scala.concurrent.duration.FiniteDuration

object RestSpec {
  sealed trait SealedCommand extends SpecificationEvent
  final case class Create(batchId: Int, orderingAccount: Iban, beneficiaryAccount: Iban, requestedExecutionDate: DateTime, amount: Money) extends SealedCommand

  object Dummy extends Specification {
    override type Event = SealedCommand
    override type Data = Unit
    override type State = ExternalAgreement.State
    override type Key = String

    override def keyable: RebelKeyable[Key] = implicitly

    override val specificationName: String = "Dummy"

    override implicit val dataEncoder: Encoder[Data] = io.circe.generic.semiauto.deriveEncoder
    override implicit val stateEncoder: Encoder[State] = io.circe.generic.extras.semiauto.deriveEnumerationEncoder
  }

  object DummyIntKey extends Specification {
    override type Event = SealedCommand
    override type Key = Int

    override def keyable: RebelKeyable[Key] = implicitly

    override val specificationName: String = "DummyIntKey"
    override type Data = Unit
    override type State = ExternalAgreement.State
    override implicit val dataEncoder: Encoder[Data] = io.circe.generic.semiauto.deriveEncoder
    override implicit val stateEncoder: Encoder[State] = io.circe.generic.extras.semiauto.deriveEnumerationEncoder
  }

  def dummyExternalAgreementSharding[S <: Specification]
  (implicit system: ActorSystem, si: SpecificationInfo[S], ed: Decoder[S#Event], sct: ClassTag[S#Event]) : RebelShardingExtension =
    new RebelShardingExtension(system) {
    // 10 * max number of nodes

    override type Spec = S

    override val entryProps: Props = Props(new ExternalAgreementActor)

    override def ask(key: spec.Key, message: RebelMessage)(implicit sender: ActorRef, timeout: Timeout): Future[Any] =
      Future.successful {
        import spec._
        message match {
          case TellState(_)         => CurrentState(ExternalAgreement.defaultState, ExternalAgreement.defaultRebelData)
          case RebelCommand(rde@RebelDomainEvent(ba, _, _))
            if ba == BookingAllowed => EntityCommandSuccess(rde)
          case c: RebelCommand[_]   => EntityCommandSuccess(c.domainEvent)
          case msg                  => throw new MatchError(msg)
        }
      }

    override def allActiveEntityIds(implicit executionContext: ExecutionContext, timeout: Timeout): Future[Set[EntityId]] = {
      Future.successful(Set("1", "2", "3"))
    }

      override def specClassTag: ClassTag[S#Event] = sct

      // hackyerdyhackerdy
      override lazy val eventDecoder: Decoder[spec.Event] = ed.map(_.asInstanceOf[spec.Event])

      override def specInfo: SpecificationInfo[S] = si
    }
}

class RestSpec extends FlatSpec with ScalatestRouteTest with Matchers with EitherMatchers with OneInstancePerTest {

  override def testConfigSource: String = "rebel.endpoints.query-timeout = 5s"

//  override def createActorSystem(): ActorSystem = TestActorSystemManager.createSystemWithInMemPersistence(testConfig)

  private val dummyShardingExtension: RebelShardingExtension = dummyExternalAgreementSharding[RestSpec.Dummy.type]
  val endpointInfos: Set[RebelEndpointInfo] =
    Set(RebelEndpointInfo(dummyShardingExtension))
  val body: Json = Encoder[Dummy.Event].apply(Create(1,Ibans.testIban1, Ibans.testIban2, DateTime.now, EUR(10)))

  "Dummy Decoders" should "decode" in {
    dummyShardingExtension.eventDecoder.decodeJson(body) shouldBe right
  }

  val extension: RebelShardingExtension = ExternalAgreementSharding(system) // dummyExternalAgreementSharding[ExternalAgreement.type]
  val externalAgreementCommandBody: Json = Encoder[ExternalAgreement.Command].apply(BookingAllowed)

  "Real Decoders" should "decode" in {
    extension.eventDecoder.decodeJson(externalAgreementCommandBody) shouldBe right
  }

  "Real Encoders" should "encode and not throw exceptions" in {
    ExternalAgreement.dataEncoder.apply(ExternalAgreement.defaultData)
    ExternalAgreement.stateEncoder.apply(ExternalAgreement.defaultState)
    ExternalAgreement.rDataEncoder.apply(ExternalAgreement.defaultRebelData)

    val cs: CurrentState[ExternalAgreement.type] = CurrentState(ExternalAgreement.defaultState, ExternalAgreement.defaultRebelData)
    ExternalAgreement.currentStateEncoder.apply(cs)
  }

  "Real RestEndPoints with account" should "respond to GET" in {
    val system2 = TestActorSystemManager.createSystemWithInMemPersistence(testConfig)
    val sharding = AccountSharding(system2)
    sharding.start()
    val endpoints: RebelRestEndPoints = new RebelRestEndPoints(system2, Set(RebelEndpointInfo(sharding)))

    implicit val t: RouteTestTimeout = RouteTestTimeout(FiniteDuration(5, TimeUnit.SECONDS))

    Get("/Account/NL1") ~> endpoints.route ~> check{
      responseAs[Json].noSpaces should be("""{"state":"New","data":{"Uninitialised":{}}}""")
      status shouldEqual StatusCodes.OK
    }
  }

  "RebelRest[ExternalAgreementActor.Command]" should "contains the command names" in {
    new RebelCommandRestEndpoint[ExternalAgreement.type].commandNames should contain only "BookingAllowed"
  }

  "RebelRestEndPoint" should "respond to GET" in {
    val restEndPoint: RebelRestEndPoints = new RebelRestEndPoints(system, endpointInfos)

    Get("/Dummy/1") ~> restEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
    }
    Get("/Dummy/1/") ~> restEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }



  "RebelCommandRestEndpoint" should "respond to POST" in {
    val commandEndpoints: RebelCommandRestEndpoint[ExternalAgreement.type] = new RebelCommandRestEndpoint[ExternalAgreement.type]

    val k = extension.keyable.stringToKey("key").get

    Post("/BookingAllowed", externalAgreementCommandBody) ~> commandEndpoints.commandRoute(extension, "BookingAllowed")(k) ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "RebelRestEndPoint" should "respond to POST" in {
    val restEndPoint: RebelRestEndPoints = new RebelRestEndPoints(system, endpointInfos)

    Post("/Dummy/1/Create", body) ~> restEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }


  it should "not crash with Sealed traits Command (with ntime DateTime and Money)" in {
    new RebelCommandRestEndpoint[Dummy.type]
  }

  it should "work with string path parameters" in {
    val restEndPoint: RebelRestEndPoints = new RebelRestEndPoints(system, endpointInfos)

    Get("/Dummy/NL1") ~> restEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  val intRestEndPoint: RebelRestEndPoints =
    new RebelRestEndPoints(system, Set(RebelEndpointInfo(dummyExternalAgreementSharding[RestSpec.DummyIntKey.type])))

  val bodyIntKey: Json = Encoder[DummyIntKey.Event].apply(Create(1,Ibans.testIban1, Ibans.testIban2, DateTime.now, EUR(10)))

  "RebelRestEndPoints[DummyIntKey]" should "work with int path parameters" in {
    Get("/DummyIntKey/1") ~> intRestEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "404 with non-int path parameters" in {
    Get("/DummyIntKey/NL1") ~> intRestEndPoint.route ~> check {
      handled shouldBe false
    }
  }

  it should "respond to POST" in {
    Post("/DummyIntKey/1/Create", bodyIntKey) ~> intRestEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "return shardids on GET" in {
    Get("/DummyIntKey") ~> intRestEndPoint.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Json].noSpaces should be("""["1","2","3"]""")
    }
  }
}
