package com.ing.rebel.util

import java.security.{Provider, SecureRandomSpi}

import it.unimi.dsi.util.XoShiRo256StarStarRandomGenerator

object XoShiRo256StarStarRandomProvider extends Provider("XoShiRo256StarStarRandomProvider", "1.0", "XoShiRo256StarStarRandom algorithm") {
  put("SecureRandom.XoShiRo256StarStarRandom","com.ing.rebel.util.XoShiRo256StarStarRandomSpi")
}

class XoShiRo256StarStarRandomSpi extends SecureRandomSpi {

  val impl: XoShiRo256StarStarRandomGenerator = new XoShiRo256StarStarRandomGenerator()

  override def engineSetSeed(seed: Array[Byte]): Unit = impl.setState(seed.map(_.toLong)) // impl.setSeed(seed)

  override def engineNextBytes(bytes: Array[Byte]): Unit = impl.nextBytes(bytes)

  override def engineGenerateSeed(numBytes: Int): Array[Byte] = {
    0.to(numBytes).map(_ => impl.nextLong().toByte).toArray
  }
}