package com.ing.rebel.util


import com.ing.rebel.util.MoneyHelper.RebelCurrency
import io.circe.{Decoder, Encoder}
import org.joda.time._
import org.joda.time.format.DateTimeFormat
import squants.market.Money

import scala.math.BigDecimal.RoundingMode
import scala.util.Try


/**
  * Single location for Circe encoders and decoders, to make sure they are compiled/resolved correctly as a single compiler unit
  */
object CirceSupport {
  private val dateTimeFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
  private val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")
  private val timeFormat = DateTimeFormat.forPattern("HH:mm:ss")

  implicit val dateTimeEncoder: Encoder[DateTime] = Encoder.encodeString.contramap(_.toString(dateTimeFormat))
  implicit val dateEncoder: Encoder[LocalDate] = Encoder.encodeString.contramap(_.toString(dateFormat))
  implicit val timeEncoder: Encoder[LocalTime] = Encoder.encodeString.contramap(_.toString(timeFormat))

  implicit val longDateTimeDecoder: Decoder[DateTime] =
    Decoder[String].map(timestamp => DateTime.parse(timestamp, dateTimeFormat))

  implicit val dateDecoder: Decoder[LocalDate] =
    Decoder[String].map(date => LocalDate.parse(date, dateFormat))

  implicit val timeDecoder: Decoder[LocalTime] =
    Decoder[String].map(time => LocalTime.parse(time, timeFormat))

  //    // TODO: Support more than euro
  //    // TODO unit tests
  //    // Design this data type better
  // TODO is this rounding mode always correct for each currency?
  implicit val moneyDecoder: Decoder[Money] =
    Decoder.decodeString.emapTry(s => Try(Money(BigDecimal(s.substring(4)), RebelCurrency(s.substring(0, 3)))))
  implicit val moneyEncoder: Encoder[Money] =
    Encoder.encodeString.contramap(money => s"${money.currency.code} ${money.amount.setScale(money.currency.formatDecimals, RoundingMode.HALF_UP)}")

}