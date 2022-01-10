package com.ing.rebel.kryo

import akka.event.Logging
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.serializers.JavaSerializer
import com.esotericsoftware.kryo.{Kryo, Serializer}
import de.javakaffee.kryoserializers.jodatime.{JodaDateTimeSerializer, JodaIntervalSerializer, JodaLocalDateSerializer, JodaLocalDateTimeSerializer}
import io.altoo.akka.serialization.kryo.DefaultKryoInitializer
import io.altoo.akka.serialization.kryo.serializer.scala.ScalaKryo
import org.joda.time.{DateTime, Interval, LocalDate, LocalDateTime}

import scala.runtime.BoxedUnit

class BoxedUnitSerializer extends Serializer[BoxedUnit] {
  override def write(kryo: Kryo, out: Output, obj: BoxedUnit): Unit = {}

  override def read(kryo: Kryo, in: Input, cls: Class[BoxedUnit]): BoxedUnit = BoxedUnit.UNIT
//    override def read(kryo: Kryo, in: Input, `type`: Class[_ <: BoxedUnit]): BoxedUnit = BoxedUnit.UNIT
}

class KryoInit extends DefaultKryoInitializer {
  override def postInit(kryo: ScalaKryo): Unit = {
    super.postInit(kryo)
    kryo.addDefaultSerializer(classOf[BoxedUnit], new BoxedUnitSerializer)
    //    kryo.addDefaultSerializer(classOf[java.lang.Throwable], new JavaSerializer())

    // joda DateTime, LocalDate and LocalDateTime
    kryo.register(classOf[DateTime], new JodaDateTimeSerializer())
    kryo.register(classOf[LocalDate], new JodaLocalDateSerializer())
    kryo.register(classOf[LocalDateTime], new JodaLocalDateTimeSerializer())
    kryo.register(classOf[Interval], new JodaIntervalSerializer())
  }
}
