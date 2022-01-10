package com.ing.corebank.rebel.simple_transaction

import com.ing.rebel.Iban
import io.circe.Encoder
import squants.market.EUR

class AllToOneSimulation extends AllToAllSimulation {
  override def feeder: Iterator[Map[String, Any]] = super.feeder.map(m =>
    // Always deposit to the same account
    if (m("from") != 1) {
      val newBook = Encoder[Transaction.Event].apply(Transaction.Book(EUR(1), Iban(s"NL${m("from").toString}"), Iban(s"NL${1.toString}")))
      m.updated("to", 1).updated("book", newBook.noSpaces).updated("requestName", categorizeTransaction(m("from").asInstanceOf[Int], 1))
    } else {
      m
    }
  )

  override lazy val scenarioName: String = "AllToOne"
}
