import com.ing.corebank.rebel.simple_transaction.{Account, Transaction}
import com.ing.rebel._
import io.circe.{Encoder, Json}
import org.joda.time.DateTime
import squants.market.EUR

def http(json: Json, spec: String, id: String) = {
  val command = json.asObject.get.fields.head
  s"""echo '${json.noSpaces}' | http -v POST ':8080/$spec/$id/$command'"""
}

val spec = "Account"

implicit def command2Json[T](a: T)(implicit encoder: Encoder[T]): Json = encoder.apply(a)

/// EDIT HERE
def items: Seq[(Json, String, String)] = Seq(
  (Encoder[Account.Event] apply Account.OpenAccount(EUR(100)), spec, "NL1"),
  (Encoder[Account.Event] apply Account.Deposit(EUR(10)), spec, "NL1"),
//  (Encoder[Transaction.Command] apply Transaction.Start,
  (Encoder[Transaction.Command] apply Transaction.Book(EUR(10), Iban("NL1", "NL"), Iban("NL2", "NL")), "Transaction", "1")
)
// NOW RUN

items.foreach(tup => println((http _).tupled(tup)))
