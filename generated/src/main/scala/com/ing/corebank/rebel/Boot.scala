// Generated @ 05-01-2017 15:18:45
package com.ing.corebank.rebel

import com.ing.corebank.rebel.sharding._
import com.ing.rebel.RebelSharding.RebelShardingExtension
import com.ing.rebel.RebelCommandRestEndpoint
import com.ing.rebel.RebelRestEndPoints.RebelEndpointInfo
import com.ing.rebel.boot.BootLib
import simple_transaction._ // TODO make generic

import io.circe.generic.auto._
import BootLib._


object Boot extends App with BootLib {
  override def endpointInfos: Set[RebelEndpointInfo] =
    Set(RebelEndpointInfo(DummySharding(system)),
      RebelEndpointInfo(SimpleNoSharding(system)),
      RebelEndpointInfo(SimpleSharding(system)),
      RebelEndpointInfo(SimpleShardingWithPersistence(system)),
      RebelEndpointInfo(TransactionSharding(system)),
      RebelEndpointInfo(AccountSharding(system)))

  applySystemProperties(argsToOpts(this.args))
 
  start()
}