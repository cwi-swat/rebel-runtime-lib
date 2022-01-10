package com.ing.rebel.util

import squants.market._

object MoneyHelper {
  //  case class OtherCurrency(override val code: String) extends Currency(code, code, code, 2)
  object RebelCurrency {
    //     quick hack to make this work for case object currencies
    def apply(currency: String): Currency = {
      currency match {
        case "EUR" => EUR
        case "USD" => USD
        case other => ???
      }
    }
  }
}
