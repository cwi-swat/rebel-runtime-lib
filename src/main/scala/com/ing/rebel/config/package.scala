package com.ing.rebel.config

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.typesafe.config.Config
import pureconfig.generic.EnumCoproductHint
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.duration.{Duration, FiniteDuration}


class RebelConfigExtension(config: Config) extends Extension {
  val rebelConfig: RebelConfig = {
    val configEither = loadConfig[RebelConfig](config.getConfig("rebel"))
    require(configEither.isRight, s"RebelConfig could not be loaded: ${configEither.left.toString}")
    configEither.right.get
  }
}
object RebelConfig extends ExtensionId[RebelConfigExtension] with ExtensionIdProvider {

  override def lookup: RebelConfig.type = RebelConfig

  override def createExtension(system: ExtendedActorSystem): RebelConfigExtension =
    new RebelConfigExtension(system.settings.config)
}


/**
  * Configuration Class to be deserialised from config
  */
case class RebelConfig(
                        visualisation: Visualisation,
                       endpoints: Endpoints,
                       clustering: Clustering,
                       sync: Sync)

case class Visualisation(enabled: Boolean)

case class Endpoints(host: String, port: Int, queryTimeout: FiniteDuration, commandTimeout: FiniteDuration, blockingDispatcher: Boolean)

case class Clustering(ip: String, port: Int, bindPort: Int)

case class Sync(twoPC: TwoPC,
                maxTransactionsInProgress: Int,
                commandDecider: CommandDecider)

case class TwoPC(managerTimeout: FiniteDuration,
                 participantTimeout: FiniteDuration,
                 participantRetryDuration: FiniteDuration,
                 managerRetryDuration: FiniteDuration,
                 lockMechanism: LockMechanism)

object LockMechanism {
  // enum support for pure conf
  implicit val lockMechanismHint: EnumCoproductHint[LockMechanism] = new EnumCoproductHint[LockMechanism]
}

sealed trait LockMechanism
case object Parallel extends LockMechanism
case object Sequential extends LockMechanism

object CommandDecider {
  implicit val lockMechanismHint: EnumCoproductHint[CommandDecider] = new EnumCoproductHint[CommandDecider]
}
sealed trait CommandDecider
case object Locking extends CommandDecider
case object Dynamic extends CommandDecider
case object StaticThenDynamic extends CommandDecider
case object StaticThenLocking extends CommandDecider

//}