name := "cassandra_reporting"

// Docker
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

maintainer in Docker := "Tim Soethout"
version in Docker := "latest"
//daemonUser in Docker := "app" // user in the Docker image which will execute the application (must already exist)
//dockerBaseImage := "java:8" // Docker image to use as a base for the application image
//dockerBaseImage := "kamon/grafana_graphite" // Docker image to use as a base for the application image
//dockerExposedPorts in Docker := Seq(8080, 8001, 2551) // Ports to expose from container for Docker container linking
//dockerRepository := Some("docker.europe.intranet:5000") // Repository used when publishing Docker image
// entrypoint
//mainClass in Compile := Some("io.gatling.app.Gatling")
//mainClass in assembly := Some("io.gatling.app.Gatling")


import com.typesafe.sbt.packager.docker.{Cmd, _}

dockerCommands := Seq(
  Cmd("FROM", "cassandra:3"),
  Cmd("MAINTAINER", (maintainer in Docker).value),
  Cmd("COPY", "opt/docker/*.jar /usr/share/cassandra/lib/"),
  Cmd("COPY", "opt/docker/metrics-reporter-config-influx.yaml /etc/cassandra/metrics-reporter-config-influx.yaml"),
  Cmd("COPY", "opt/docker/jmxtrans-agent.xml /etc/cassandra/"),

  Cmd("RUN", """sed -i 's/JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote.authenticate=true"/""" +
    """JVM_OPTS="$JVM_OPTS""" +
    """ -XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=1 -XshowSettings:vm""" +
    """ -Dcom.sun.management.jmxremote.authenticate=false -javaagent:\/usr\/share\/cassandra\/lib\/jmxtrans-agent-1.2.6.jar=\/etc\/cassandra\/jmxtrans-agent.xml""" +
//    """ -Dcassandra.consistent.rangemovement=false""" +
//    """ -Dcassandra.auto_bootstrap=false""" +
    """"/g' /etc/cassandra/cassandra-env.sh"""),

  Cmd("RUN", """sed -i '/password/d' /etc/cassandra/cassandra-env.sh"""),
//
//  Cmd("ENV", "JAVA_OPTS=\"$JAVA_OPTS -javaagent:/usr/share/cassandra/lib/jmxtrans-agent-1.2.6.jar=/etc/cassandra/jmxtrans-agent.xml\""),

  ExecCmd("CMD", "cassandra", "-f", "-Dcassandra.metricsReporterConfigFile=metrics-reporter-config-influx.yaml", "-Dcassandra.consistent.rangemovement=false", "-Dcassandra.auto_bootstrap=false")
)


// Docker on AWS ECR
import com.amazonaws.regions.{Region, Regions}

enablePlugins(EcrPlugin)
region in Ecr := Region.getRegion(Regions.EU_CENTRAL_1)
repositoryName in Ecr := (packageName in Docker).value
localDockerImage in Ecr := (packageName in Docker).value + ":" + (version in Docker).value
version in Ecr := (version in Docker).value
repositoryTags in Ecr += version.value.replace('+', '-')

//repositoryTags in Ecr += version.value

// Create the repository before authentication takes place (optional)
login in Ecr := ((login in Ecr) dependsOn (createRepository in Ecr)).value

// Authenticate and publish a local Docker image before pushing to ECR
push in Ecr := ((push in Ecr) dependsOn (publishLocal in Docker, login in Ecr)).value