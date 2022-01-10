package com.ing

import akka.persistence.fsm.PersistentFSM.FSMState
import cats.data.{NonEmptyChain, Validated, ValidatedNec}
import com.ing.rebel.SpecificationEvent
import com.ing.rebel.kryo.SerializeWithKryo
import com.ing.rebel.specification.{EventLink, Specification}
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import io.circe.{Decoder, Encoder}
import org.joda.time.DateTime
import squants.Percent

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * Rebel types and utils
  */
package object rebel {

  final case class Iban(iban: String, countryCode: String = "NL") {
    override val toString: String = iban
    //
    //    private val MinimumLength = 15
    //    private val MaximumLength = 34
    //    private val Divisor = 97

    //    def isCorrectIban(iban : String) : Boolean = {
    //      val shuffledIban: String = iban.substring(4) + iban.substring(0, 4)
    //      BigInt(shuffledIban.map(_.asDigit).mkString) % Divisor == 1
    //    }

    // TODO re-enable requires when used for real
    //    require(iban.length >= MinimumLength && iban.length <= MaximumLength,
    //      s"IBAN number does not meet length boundary requirement: [$MinimumLength, $MaximumLength]")
    //    require(isCorrectIban(iban), s"$iban is an invalid IBAN number")
  }

  object Iban {
    implicit val iban: RebelKeyable[Iban] = {
      RebelKeyable[Iban](
        s => Try(Iban(s)).toOption
      )
    }

    implicit val decoder: Decoder[Iban] = Decoder.decodeString.map(Iban(_))
    implicit val encoder: Encoder[Iban] = Encoder.encodeString.contramap(_.iban)

  }

  sealed trait RebelData[+A] {
    // redeclare map to keep the container type `RebelData`
    def map[B](f: A => B): RebelData[B]

    def toOption: Option[A]
  }
  // Lift RebelData to piggyback on Option features such as `exists`
  object RebelData {

    import scala.language.implicitConversions

    implicit def rebelData2Option[A](rebelData: RebelData[A]): Option[A] = rebelData.toOption
  }

  final case object Uninitialised extends RebelData[Nothing] {
    override def map[B](f: Nothing => B): RebelData[B] = Uninitialised

    override def toOption: Option[Nothing] = None
  }
  final case class Initialised[A](data: A) extends RebelData[A] {
    override def map[B](f: (A) => B): RebelData[B] = Initialised(f(data))

    override def toOption: Option[A] = Some(data)
  }

  type RebelSuccess = Unit
  type RebelCheck[A] = ValidatedNec[RebelError, A]
  object RebelCheck {
    def success[A](a:A) : RebelCheck[A] = Validated.Valid(a)
   }
  type RebelErrors = NonEmptyChain[RebelError]
  object RebelErrors {
    def of(rebelError: RebelError): RebelErrors = NonEmptyChain.one(rebelError)
  }
  // type to signal success or non-empty list of errors
  type RebelConditionCheck = RebelCheck[RebelSuccess]
  object RebelConditionCheck {
    val success: RebelConditionCheck = Validated.valid(())

    def failure[A](error: RebelError): RebelCheck[A] = Validated.invalidNec(error)

    def failure[A](errors: RebelErrors): RebelCheck[A] = Validated.invalid(errors)
  }

  trait RebelState extends FSMState with Product {
    override val identifier: String = this.productPrefix
  }

  /**
    * Operations that can be part of a sync
    * covariant because it could contain an event from another specification
    */
  sealed trait RebelSyncEvent[+S <: Specification] {
    def transactionId: TransactionId
  }

  /**
    * Event in specification
    */
  trait SpecificationEvent

  /**
    * Entity that is persisted including timestamp, for correct replaying and stable Rebel `now` over events.
    *
    * @param timestamp the relevant Rebel `now` of the command/event
    * @param transactionId transaction id
    * @param specEvent   the Rebel command
    */
  final case class RebelDomainEvent[S <: Specification](
     specEvent: S#Event,
     timestamp: DateTime = DateTime.now(),
     transactionId: TransactionId = TransactionId(UniqueId("t"))) extends RebelSyncEvent[S] with SerializeWithKryo

  object RebelDomainEvent {
    // No default values, because otherwise compiler complains over multiple methods with default values
    def apply[Event <: SpecificationEvent, S <: Specification](specEvent: Event)
                                                              (implicit link: EventLink[Event, S], evidence: Event <:< S#Event): RebelDomainEvent[S] =
      this.apply(specEvent, TransactionId(UniqueId("t")))
    def apply[Event <: SpecificationEvent, S <: Specification](specEvent: Event, transactionId: TransactionId)
                                                              (implicit link: EventLink[Event, S], evidence: Event <:< S#Event): RebelDomainEvent[S] = {
      val event: S#Event = evidence(specEvent)
      RebelDomainEvent[S](event, transactionId = transactionId)
    }
//    def apply[S <: Specification](specEvent: S#Event,
//                                  timestamp: DateTime = DateTime.now(),
//                                  transactionId: TransactionId = TransactionId(UniqueId("t"))) : RebelDomainEvent[S] =
//      new RebelDomainEvent(specEvent, timestamp, transactionId)
  }

  sealed trait QuerySyncEvent[S <: Specification] extends RebelSyncEvent[S]

  case class IsInitialized[S <: Specification](transactionId: TransactionId) extends QuerySyncEvent[S]

  case class InState[S <: Specification](transactionId: TransactionId, state: S#State) extends QuerySyncEvent[S]

  /**
    * Helper class for nicer percentage writing
    *
    * @param n percentage between 0-100
    * @tparam N type that can be converted to Float
    */
  implicit class Percentage[N: Numeric](val n: N) {
    def percent: Double = Percent(n).value / 100 //  implicitly[Numeric[N]].toFloat(n) / 100
  }

  // Java Duration to Scala Duration
  implicit class RichJavaDuration(d: java.time.Duration) {
    def asScala: FiniteDuration = scala.concurrent.duration.Duration.fromNanos(d.toNanos)
  }
}
