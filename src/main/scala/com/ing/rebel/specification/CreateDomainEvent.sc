import com.ing.rebel.specification.Specification
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.TransactionId
import com.ing.rebel.{Iban, EventLink, RebelDomainEvent, RebelKeyable, RebelState, SpecificationEvent}
import io.circe.generic.extras.Configuration
import org.joda.time.DateTime
import squants.market.{EUR, Money}

object Account extends Account

trait Account extends Specification {
  case class AccountData(balance: Money)

  type Key = Iban

  override def keyable: RebelKeyable[Key] = implicitly

  override type State = AccountState
  override type Data = AccountData
  override type Event = AccountEvent

  sealed trait AccountState extends RebelState
  case object New extends AccountState
  case object Opened extends AccountState

  sealed trait AccountEvent extends SpecificationEvent

  final case class OpenAccount(accountNr: Iban, initialDeposit: Money) extends AccountEvent
  final case class Deposit(amount: Money) extends AccountEvent
}

// Links events back to specification
//class Link[-Event <: SpecificationEvent, S <: Specification](implicit evidence: Event <:< S#Event) {
//  def create(specEvent: Event): RebelDomainEvent[S] = {
//    val event: S#Event = evidence(specEvent)
//    new RebelDomainEvent[S](event, DateTime.now(), TransactionId(UniqueId("t")))
//  }
//}

object RebelDomainEvent {
  def apply[Event <: SpecificationEvent, S <: Specification](
                                                              specEvent: Event,
                                                              timestamp: DateTime = DateTime.now(),
                                                              transactionId: TransactionId = TransactionId(UniqueId("t"))
                                                            )(implicit link: EventLink[Event, S], evidence: Event <:< S#Event): RebelDomainEvent[S] = {
    val event: S#Event = evidence(specEvent)
    new RebelDomainEvent[S](event, timestamp, transactionId)
  }
}

implicitly[Account.Event <:< Account#Event]

val deposit: Account.Event = Account.Deposit(EUR(10))

RebelDomainEvent(deposit)

RebelDomainEvent(Account.Deposit(EUR(10)))
RebelDomainEvent(Account.OpenAccount(Iban("NL"), EUR(10)))

object Transaction extends Specification {
  sealed trait SpecEvent extends SpecificationEvent
  case class Start(id: Int, amount: Money, from: Iban, to: Iban, bookOn: DateTime) extends SpecEvent
  case object Book extends SpecEvent
  case object Fail extends SpecEvent
  override type Event = SpecificationEvent


  override type Data = Unit
  case class State() extends RebelState
  override type Key = String
//  override val specificationName =
  override def keyable = implicitly

}


RebelDomainEvent(Transaction.Book)

Transaction.specificationName
"Trans$action$".replaceAll("\\$$","")