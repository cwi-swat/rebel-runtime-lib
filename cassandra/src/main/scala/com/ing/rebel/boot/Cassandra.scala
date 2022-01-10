package com.ing.rebel.boot

import java.io.File

import akka.persistence.cassandra.testkit.CassandraLauncher

object Cassandra extends App {
  val cassandraDirectory = new File("target/cassandra")
  // todo maybe use embedded launcher?
  CassandraLauncher.start(
    cassandraDirectory,
    configResource = CassandraLauncher.DefaultTestConfigResource,
    clean = true,
    port = 9042,
    classpath = CassandraLauncher.classpathForResources("logback.xml")
  )

  // forked apparently, to keep from exiting
  Thread.sleep(Long.MaxValue)
}