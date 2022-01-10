package com

import com.typesafe.config.Config
import io.circe.Encoder

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

package object ing {
  implicit val durationEncoder: Encoder[FiniteDuration] = Encoder[String].contramap(_.toString)
  implicit val configEncoder: Encoder[Config] =
    Encoder[Map[String, String]].contramap(_.entrySet().asScala.map(e => e.getKey -> e.getValue.unwrapped().toString).toMap)
}
