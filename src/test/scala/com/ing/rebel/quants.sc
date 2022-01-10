//import com.ing.rebel.{EUR, Money}
import com.ing.rebel.{RebelCommand}
import io.circe.{Decoder, Encoder, Json}
import org.scalatest.Matchers
import squants.{Money, Percent}
import squants.market.{EUR, Money}

import scala.util.Try
//import squants.market._
import squants.market.MoneyConversions._
//import com.ing.rebel.Money

//EUR(100)
//
//100.euros
//100.EUR
//
//100.dollars times 10
//
////100.dollars + 100.euros
//
val m: Money = EUR(50)
//m * 50
//m * 50f
//m * 50.00
//
//val p = Percent(5000)
//
//p * m
//
//m * p.value / 100

//implicit val decoder: Decoder[Money] = Decoder.decodeString.emapTry(s => Try(squants.market.Money(BigDecimal(s.substring(4)), RebelCurrency(s.substring(0,3)))))
//implicit val encoder: Encoder[Money] = Encoder.encodeString.contramap(money => s"${money.currency.code} ${money.amount.setScale(money.currency.formatDecimals)}")

import com.ing.rebel.util.CirceSupport._
import io.circe.generic.auto._

Encoder[Money].apply(m).noSpaces

Decoder[Money].decodeJson(Json.fromString("EUR 100"))

sealed trait Command extends RebelCommand
final case class OpenAccount(initialDeposit : Money) extends Command

Encoder[Command].apply(OpenAccount(EUR(100))).noSpaces


//object x extends Matchers {
//  //"Money" should "be multiplying" in {
////  private val value1 = m * m
////  value1 should be(EUR(2500))
//
//  m * 50 should be(EUR(2500))
//  m * 50f should be(EUR(2500))
//  m * 50.00 should be(EUR(2500))
//
//  //    50 * m should be (EUR(2500))
//  //    50.00f * m should be (EUR(2500))
//  //    50.00 * m should be (EUR(2500))
//
////  m * 5000.percent should be(EUR(2500))
//}