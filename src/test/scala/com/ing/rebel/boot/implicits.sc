import com.ing.example.Transaction.{SpecEvent, Start}
import com.ing.rebel.{EUR, Iban, RebelCommand, _}
import io.circe.Encoder
import io.circe.generic.auto._
import org.joda.time.DateTime

val start = Start(1, EUR(10), Iban("NL1", "NL"), Iban("NL2", "NL"), DateTime.now())

Encoder[SpecEvent].apply(start)



