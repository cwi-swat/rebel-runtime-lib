package com.ing.rebel.util

import com.ing.rebel.util.MoneyHelper.RebelCurrency
import org.scalatest.{FlatSpec, FunSuite, Matchers}
import squants.market.Money
import CirceSupport._
import io.circe.Json

class CirceSupportTest extends FlatSpec with Matchers {

  "moneyEncoder" should "serialise Money correct number of digits and rounded down" in {
    val m = Money(100f/3, RebelCurrency("EUR"))
    val json = moneyEncoder(Money(100f/3, RebelCurrency("EUR")))
    json shouldBe Json.fromString("EUR 33.33")
  }

}
