name := "grafana_influxdb"

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


import com.typesafe.sbt.packager.docker._

dockerCommands := Seq(
  Cmd("FROM", "samuelebistoletti/docker-statsd-influxdb-grafana"),
  Cmd("MAINTAINER", (maintainer in Docker).value),
  // used to tweak graphite settings a bit, so storage (and rsync) is not too big
  // TODO increase this if your test run longer!
//  Cmd("RUN", "sed -i -e 's/retentions.*/retentions = 1s:30m/' /opt/graphite/conf/storage-schemas.conf")
//  Cmd("COPY", "opt/docker/supervisord.conf /etc/supervisor/conf.d/supervisord.conf"),
  Cmd("COPY", "opt/docker/influxdb.conf /etc/influxdb/influxdb.conf"),
  Cmd("RUN", "sed -i -e 's/type = mysql/type = sqlite3/' /etc/grafana/grafana.ini")
)


// Docker on AWS ECR
import com.amazonaws.regions.{Region, Regions}

enablePlugins(EcrPlugin)
region in Ecr := Region.getRegion(Regions.EU_CENTRAL_1)
repositoryName in Ecr := (packageName in Docker).value
localDockerImage in Ecr := (packageName in Docker).value + ":" + (version in Docker).value
version in Ecr := (version in Docker).value //.replace('+', '-')

repositoryTags in Ecr += version.value.replace('+', '-')
// Create the repository before authentication takes place (optional)
login in Ecr := ((login in Ecr) dependsOn (createRepository in Ecr)).value

// Authenticate and publish a local Docker image before pushing to ECR
push in Ecr := ((push in Ecr) dependsOn (publishLocal in Docker, login in Ecr)).value

// test jar, needed for perf-docker
//sourceDirectory in Compile := (sourceDirectory in Gatling).value
