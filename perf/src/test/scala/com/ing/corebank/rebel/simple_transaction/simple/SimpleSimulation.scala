package com.ing.corebank.rebel.simple_transaction.simple

import com.ing.corebank.rebel.sharding.{Simple, SimpleActor}
import com.ing.corebank.rebel.simple_transaction.{Account, SimulationBase}
import io.circe.Encoder
import io.circe.generic.auto._
import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef.http
import squants.market.EUR

import scala.util.Random


trait SimpleSetup {
  this: SimulationBase =>
  val json: String = Encoder[Simple.Event].apply(Simple.OnlyCommand).noSpaces
  val prefix: String = new Random().alphanumeric.take(5).mkString
  val feeder: Iterator[Map[String, Any]] = Iterator.from(1) map (i => Map("id" -> s"$prefix-$i"))

  def simpleChain(endPointName: String): ChainBuilder =
    feed(feeder)
      .exec(http(endPointName).post(session => {
        beforeSystem.log.debug("Creating HTTP request for {}/{}", endPointName, session("id").as[String])
        s"/$endPointName/${session("id").as[String]}/OnlyCommand"
      }).body(StringBody(json)).asJson)

}

class DummySimulation extends SimulationBase with SimpleSetup {
  override val scenarioName: String = "Bare"
  override val chain: ChainBuilder = simpleChain("Dummy")
  start()
}

class SimpleSimulation extends SimulationBase with SimpleSetup {
  override val scenarioName: String = "Simple"
  override val chain: ChainBuilder = simpleChain("SimpleNo")
  start()
}

/**
  * Test case which has no sync/depencies on other specifications
  */
class SimpleShardingSimulation extends SimulationBase with SimpleSetup {
  override val scenarioName: String = "SimpleSharding"
  override val chain: ChainBuilder = simpleChain("Simple")
  start()
}

class SimpleWithPersistenceSimulation extends SimulationBase with SimpleSetup {
  override val scenarioName: String = "SimpleWithPersistence"
  override val chain: ChainBuilder = simpleChain(scenarioName)
  start()
}

class OpenAccountSimulation extends SimulationBase {
  val open: String = Encoder[Account.Event].apply(Account.OpenAccount(EUR(100000))).noSpaces

  val prefix: String = new Random().alphanumeric.take(5).mkString
  val feeder: Iterator[Map[String, Any]] = Iterator.from(1).map(i => Map("id" -> s"$prefix-NL$i"))

  override val scenarioName: String = "OpenAccount"

  override val chain: ChainBuilder =
    feed(feeder)
      .exec(http("openAccount").post(session => s"/Account/${session("id").as[String]}/OpenAccount").body(StringBody(open)).asJson)

  start()
}