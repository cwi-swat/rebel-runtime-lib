package com.ing.rebel

import org.scalatest.{FlatSpec, Matchers}
import squants.Percent
import squants.market.{EUR, Money}

class MoneyTest extends FlatSpec with Matchers {

  val m: Money = EUR(50)

  "Money" should "be multiplying" in {
//    m * m  should be (EUR(2500))

    m * 50 should be (EUR(2500))
    m * 50f should be (EUR(2500))
    m * 50.00 should be (EUR(2500))

//    50 * m should be (EUR(2500))
//    50.00f * m should be (EUR(2500))
//    50.00 * m should be (EUR(2500))

    Percent(5000) * m should be (EUR(2500))
    m * 5000.percent should be (EUR(2500))
  }

}
