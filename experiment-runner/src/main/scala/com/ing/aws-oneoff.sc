import java.util

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder
import com.amazonaws.services.ec2.model.{Volume => _, _}
import com.amazonaws.services.ecs.model._
import com.amazonaws.services.ecs.{AmazonECS, AmazonECSClient, AmazonECSClientBuilder}
import com.amazonaws.services.logs.AWSLogsClientBuilder
import com.amazonaws.services.logs.model.{GetLogEventsRequest, OutputLogEvent}
import com.amazonaws.util.EC2MetadataUtils

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import com.ing.rebel.experiment.aws.AwsLib.{awsLogs, _}
import com.ing.rebel.experiment.aws.AwsLib.Db._


// TODO start or scale cluster
// TODO open ports to each other with group security group

//val instances: ListContainerInstancesResult = ecs.listContainerInstances(new ListContainerInstancesRequest().withCluster(cluster))
//instances.getContainerInstanceArns.size()

// cleanup

//val metricsTasks = ecs.listTasks(new ListTasksRequest().withCluster(cluster).withFamily("metrics"))

//if (!metricsTasks.getTaskArns.isEmpty)
//val tasks: DescribeTasksResult = ecs.describeTasks(new DescribeTasksRequest().withCluster(cluster).withTasks(metricsTasks.getTaskArns))
//tasks.getTasks.asScala.head.getTaskArn

//val allInstances = ecs.describeContainerInstances(new DescribeContainerInstancesRequest().withCluster(cluster).withContainerInstances(queryContainerInstances().getContainerInstanceArns))

//removeAllReservedAttributes()

//reserveContainerInstances(2, "UNQIEUSTRING")

//ecs.describeContainerInstances(new DescribeContainerInstancesRequest().withCluster(cluster).withContainerInstances(queryContainerInstances().getContainerInstanceArns))

//val awsLogs = AWSLogsClientBuilder.defaultClient()
//
//
//val prefixName = "seed"
//val containerName = "seed"
//val ecsTaskId = "e0845e61-8354-4092-ba74-db33ef57f8ce" //tasks.getTasks.asScala.head.getTaskArn
//val logStreamName = s"$prefixName/$containerName/$ecsTaskId"
//
//
//def getAllEvents(token: String) : Seq[OutputLogEvent] = {
//  val events = awsLogs.getLogEvents(new GetLogEventsRequest().withLogGroupName(awsLogsGroup).withLogStreamName(logStreamName).withStartFromHead(true).withNextToken(token))
////  println(events.getNextBackwardToken)
////  println(events.getNextForwardToken)
//  if(events.getNextForwardToken == token) {
//    events.getEvents.asScala
//  } else {
//    events.getEvents.asScala ++ getAllEvents(events.getNextForwardToken)
//  }
//}
//getAllEvents(null)

cleanupRunningTasks(Set(), "d0d07991-167c-4baf-94bf-26a34e371c48")



Thread.sleep(2000)