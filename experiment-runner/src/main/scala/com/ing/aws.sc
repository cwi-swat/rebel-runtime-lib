import com.amazonaws.services.ec2.model.{Instance, Volume => _}
import com.amazonaws.services.ecs.model._
import com.ing.rebel.experiment.aws.AwsLib.Db._
import com.ing.rebel.experiment.aws.AwsLib.{instances, _}
import ammonite.ops.ImplicitWd._
import ammonite.ops._
import org.joda.time.DateTime
import scala.concurrent.duration._

import scala.collection.immutable
import scala.concurrent.{Await, Future}

/**
  * Script assumes you have ssh-access to the container instances without password (key authentication)
  *
  * Start containers with: `aws ec2 request-spot-fleet --spot-fleet-request-config file://spotcontainerinstances.json`
  */

// $ecs-cli up --capability-iam --keypair Futon -f --instance-type t2.medium --size 4
// TODO start or scale cluster
// TODO open ports to each other with group security group

val instances: ListContainerInstancesResult = ecs.listContainerInstances(new ListContainerInstancesRequest().withCluster(cluster))
val nrOfInstances = instances.getContainerInstanceArns.size()

val localDir: String = "/Users/tim/workspace/account-modelling/ing-codegen-akka/"
val nrOfNodes = 4

// seed node, performance/metrics node, db node {
if (nrOfInstances < nrOfNodes + 3) {
  println(s"WARNING: Not enough container instances available for all nodes: $nrOfInstances, but required ${nrOfNodes + 3}")
}
// cleanup
// TODO filter does not work
cleanupRunningTasks(_.contains("metrics"))

// METRICS
// TODO check if metrics are already running
//scp grafana/grafana.db ec2-user@35.158.83.128:
//ssh ec2-user@35.158.83.128 sudo mv grafana.db /opt/grafana/data/
val containerInstanceArn: String = instances.getContainerInstanceArns.get(Metrics.containerIndex)
val instance: Instance = getEc2Instance(containerInstanceArn)
val publicMetricsIp: String = instance.getPublicIpAddress
val metricsConnectString: String = s"ec2-user@$publicMetricsIp"
%%('scp, "-o StrictHostKeyChecking=no", "-o UserKnownHostsFile=/dev/null", s"$localDir/grafana/grafana.db", metricsConnectString + ":")
%%('ssh, "-o StrictHostKeyChecking=no", "-o UserKnownHostsFile=/dev/null", metricsConnectString, "sudo mkdir --parents /opt/grafana/data/; sudo mv grafana.db /opt/grafana/data/")


val metrics: Task = Metrics.startIfNotRunning()
val metricsGraphitePort = getHostPort(metrics, 2003)
val metricsStatsdPort = getHostPort(metrics, 8125)
val metricsIp = getEc2Instance(metrics).getPrivateIpAddress

// DB
val dbRunningTask: Task = Db.start(metricsIp, metricsStatsdPort)
val dbPort = getHostPort(dbRunningTask, cassandraPort)
val dbIpAddress = getEc2Instance(dbRunningTask).getPrivateIpAddress

// SEED
val seedRunningTask: Task = Seed.start(dbPort, dbIpAddress, metricsIp, metricsStatsdPort)
val seedIpAddress = getEc2Instance(seedRunningTask).getPrivateIpAddress
val seedPort = getHostPort(seedRunningTask, 2551)

// NODES
// parallel
val nodeIpsAndAwait = 0 until nrOfNodes map { i =>
  val nodeRunningTask = Node.start(dbPort, dbIpAddress, seedIpAddress, seedPort, metricsIp, metricsStatsdPort, Node.containerIndexIdFrom + i)
  val nodeIpAddress = getEc2Instance(nodeRunningTask._1).getPrivateIpAddress
  (nodeIpAddress, nodeRunningTask._2)
}

val nodeIps = nodeIpsAndAwait.map(_._1)
println(s"Starting ${nodeIps.size} nodes: $nodeIps")

//val nodeIpAddress: String = nodeIps.head
import scala.concurrent.ExecutionContext.Implicits.global
Await.result(Future.sequence(nodeIpsAndAwait.map(_._2)), 60.seconds)
println("All nodes started")

// PERFORMANCE TEST
// poor mans scenario info
// touch file with scenario info
val AwsInfo = "c4.large"
val totalAkkaNodes = nrOfNodes + 1
val comment = ""
%%('touch, s"$localDir/gatling/results/${DateTime.now().toDateTimeISO}-test-$AwsInfo-${totalAkkaNodes}nodes-$comment")

val performanceRunningTask: Task = PerformanceTest.start(seedIpAddress +: nodeIps, metricsIp, metricsGraphitePort)

println(s"Running performance test: $performanceRunningTask")
val performanceIp: String = getEc2Instance(performanceRunningTask).getPublicIpAddress

val performanceFinishedTask: Task = retryUntilInState(
  ecs.describeTasks(new DescribeTasksRequest().withCluster(cluster).withTasks(performanceRunningTask.getTaskArn)).getTasks.get(0), "STOPPED")

println(s"Performance test finished: $performanceFinishedTask")

// Pull results to local machine
// TODO dump info on what is in this test.
%%('ssh, "-o StrictHostKeyChecking=no", s"ec2-user@$performanceIp", "sudo yum -y install rsync")
//val output = %%('scp, "-r", "ec2-user@52.59.114.77:/opt/gatling/results", "/Users/tim/workspace/account-modelling/ing-codegen-akka/gatling")
val output = %%('rsync, "-avrz", "--progress", s"ec2-user@$performanceIp:/opt/gatling/results", s"$localDir/gatling")





println(s"Results copied to local: $output")
