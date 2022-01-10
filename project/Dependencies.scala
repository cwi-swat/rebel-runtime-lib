import sbt.Keys.scalaVersion
import sbt._

object Dependencies {
  val akkaV = "2.6.1"
  val akkaHttpV = "10.1.11"
  val circeVersion = "0.12.3"
  val cassandraPersistenceVersion = "0.102"

  val scalaTestVersion = "3.1.0"

  val kryo = Seq(
    "io.altoo" %% "akka-kryo-serialization" % "1.1.0" excludeAll ExclusionRule("com.esotericsoftware", "kryo-shaded"), // exclude to take kryo 4.x.x from kryo-serializers
    "de.javakaffee" % "kryo-serializers" % "0.43" // weird stuff happening with ClassNotFoundException for 0.45, stay here for now
  )

}