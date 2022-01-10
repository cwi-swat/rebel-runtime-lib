// Generated @ 05-01-2017 15:18:45

package com.ing.corebank.rebel.simple_transaction.actor

import akka.actor._
import akka.pattern.ask
import com.ing.corebank.rebel.sharding._
import com.ing.corebank.rebel.simple_transaction.Account.{Deposit, Withdraw}
import com.ing.corebank.rebel.simple_transaction.{Account, _}
import com.ing.rebel.{Iban, RebelDomainEvent}
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.specification.RebelSpecification
import com.ing.rebel.sync.RebelSync.UniqueId
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Initialize, ParticipantId}
import io.circe.{Decoder, Encoder}

import scala.concurrent.{Await, Future}

object TransactionActor {
  val props = Props(new TransactionActor)
}

import com.ing.corebank.rebel.simple_transaction.Transaction._
import io.circe.generic.auto._

class TransactionActor extends RebelFSMActor[Transaction.type] with TransactionLogic
