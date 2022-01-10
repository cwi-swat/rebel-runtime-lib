package com.ing.rebel.kryo

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.serialization.SerializationExtension
import cats.data.{Validated, _}
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{Input, Output}
import com.github.nscala_time.time.StaticDateTimeZone
import com.ing.example.Account.AccountData
import com.ing.example.{Account, Transaction}
import com.ing.rebel.RebelError.GenericRebelError
import com.ing.rebel.messages.{CheckPreconditionsResult, CurrentState}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TransactionManager.AbortReasonsAdded
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit._
import com.ing.rebel.sync.twophasecommit.{ActorRefContactPoint, TransactionManager, TwoPhaseCommit}
import com.ing.rebel.{Initialised, RebelCheck, RebelConditionCheck, RebelData, RebelDomainEvent, RebelError, RebelErrors, RebelSyncEvent, SpecificationEvent, Uninitialised}
import io.altoo.akka.serialization.kryo.KryoSerializer
import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import squants.market.EUR

case class Test(s: String)
case class ValidatedWrapper(v: ValidatedNel[String, Int])

case class EitherWrapper(v: Either[String, Int])

class KryoSpec extends AnyFlatSpecLike with Matchers {
  val system: ActorSystem = ActorSystem()

  val kryo: KryoSerializer = new KryoSerializer(SerializationExtension(system).system)

  "ScalaKryo" should "preserve Nil equality" in {
    val deserializedNil = roundTrip(Nil)
    assert(deserializedNil eq Nil)
  }

  it should "preserve `()` equality" in {
    roundTrip(())
  }

  it should "handle nested Either" in {
    val left: Either[String, Int] = Left("s")
    val right: Either[String, Int] = Right(1)

    roundTrip(EitherWrapper(left))
    roundTrip(EitherWrapper(right))
  }

  it should "handle nested Validated" in {
    val valid: ValidatedNel[String, Int] = Validated.valid(1)
    val invalid: ValidatedNel[String, Int] = Validated.invalidNel("errrr")

    val v = roundTrip(valid)
    val iv = roundTrip(invalid)

    val wrappedv: Option[ValidatedNel[String, Int]] = Some(valid)
    val wrappediv: Option[ValidatedNel[String, Int]] = Some(valid)

    val wv = roundTrip(wrappedv)
    val wiv = roundTrip(wrappediv)

    roundTrip(Test("test"))

    roundTrip(ValidatedWrapper(valid))
    roundTrip(ValidatedWrapper(invalid))

  }

  it should "handle CheckPreconditionsResult" in {
    roundTrip(1, CheckPreconditionsResult(TransactionId(UniqueId.apply()), RebelCheck.success(Set())))
  }

  it should "handle joda DateTime" in {
    roundTrip(StaticDateTimeZone)
    roundTrip(DateTimeZone.UTC)
    roundTrip(DateTime.now())
  }


  it should "handle RebelDomainEvent" in {
    roundTrip(RebelDomainEvent(Transaction.Book))
  }

  it should "hande 2PC messages" in {
    val actorRef = system.deadLetters
    val syncEvent: RebelSyncEvent[Transaction.type] = RebelDomainEvent(Transaction.Book)
    val tId = TransactionId("tId")
    roundTrip(SyncAction(ParticipantId(tId, "pId"), ActorRefContactPoint(actorRef), syncEvent))
    roundTrip(Seq[SyncOperation]())
    roundTrip(TransactionManager.Initialized(actorRef, tId, Set[SyncOperation]()))
    val syncCommands: Set[SyncOperation] = Set(SyncAction(ParticipantId(tId, "pId"), ActorRefContactPoint(actorRef), syncEvent))
    roundTrip(TransactionManager.Initialized(actorRef, tId, syncCommands))

    roundTrip(TwoPhaseCommit.Initialize(tId, Set(SyncAction(ParticipantId(tId, "pId"), ActorRefContactPoint(actorRef), syncEvent))))
  }

  it should "handle AbortReasonsAdded" in {
    val e = new NullPointerException("message")
    e.addSuppressed(new IllegalArgumentException("sds"))
    roundTrip(AbortReasonsAdded(RebelErrors.of(GenericRebelError(e))))
  }

  it should "handle CurrentStateInternal" in {
    import com.ing.rebel._
    import io.circe.generic.auto._
    import Account._
    val internalState = CurrentState(Account.Opened, Initialised(Account.AccountData(Some(EUR(100)))))
    roundTrip(internalState)
  }

  it should "handle Uninitialized CurrentState" in {
    import com.ing.rebel._
    import io.circe.generic.auto._
    import Account._
    val internalState = CurrentState(Account.New, Uninitialised: RebelData[AccountData])
    roundTrip(internalState)
  }


  def roundTrip[T](obj: T): T = {
    val output: Array[Byte] = kryo.toBinary(obj.asInstanceOf[AnyRef])
    val obj1 = kryo.fromBinary(output)

    assert(obj === obj1)

    obj1.asInstanceOf[T]
  }
}