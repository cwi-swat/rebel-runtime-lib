package com.ing.rebel.boot

import java.net.NetworkInterface

import scala.collection.JavaConverters._

object HostIP {

  /**
    * @return the ip address if it's a local address (172.16.xxx.xxx, 172.31.xxx.xxx , 192.168.xxx.xxx, 10.xxx.xxx.xxx)
    */
  def load(): Option[String] = {
    val interfaces: Iterator[NetworkInterface] = NetworkInterface.getNetworkInterfaces.asScala
    val interface: Option[NetworkInterface] = interfaces.find(_.getName equals "eth0")

    interface flatMap { inet =>
      // the docker address should be siteLocal
      inet.getInetAddresses.asScala.find(_.isSiteLocalAddress).map(_.getHostAddress)
    }
  }
}
