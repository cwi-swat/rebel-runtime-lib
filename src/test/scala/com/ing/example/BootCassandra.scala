package com.ing.example

import com.ing.example.sharding.{AccountSharding, TransactionSharding}
import com.ing.rebel.RebelRestEndPoints.RebelEndpointInfo
import com.ing.rebel.boot.BootLib
import BootLib._

object BootCassandra extends App with BootLib {
  override def endpointInfos: Set[RebelEndpointInfo] =
    Set(RebelEndpointInfo(TransactionSharding(system)),
      RebelEndpointInfo(AccountSharding(system)))

  applySystemProperties(argsToOpts(this.args))

  start()

}