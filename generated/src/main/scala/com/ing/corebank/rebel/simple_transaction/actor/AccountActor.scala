// Generated @ 05-01-2017 15:18:44

package com.ing.corebank.rebel.simple_transaction.actor

import akka.actor._
import akka.pattern.ask
import com.ing.corebank.rebel.simple_transaction._
import com.ing.rebel.RebelSharding.RebelShardingExtension

import scala.reflect.ClassTag
import com.ing.rebel._
import com.ing.rebel.util.CirceSupport._
import io.circe.Decoder
import io.circe.generic.auto._
import AccountActor._
import org.joda.time.{Days, Months, Seconds}
import com.github.nscala_time.time.Imports._
import squants.market._

import collection.Map
import scala.concurrent.Await
import scala.concurrent.duration._
import cats.implicits._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import com.ing.corebank.rebel._
import com.ing.corebank.rebel.sharding._
import com.ing.rebel.actors.RebelFSMActor
import com.ing.rebel.specification.RebelSpecification
import com.ing.rebel.sync.twophasecommit.TwoPhaseCommit.{Initialize, TransactionResult}

object AccountActor {
  val props = Props(new AccountActor)
}

import Account._
class AccountActor extends RebelFSMActor[Account.type] with AccountLogic

   
