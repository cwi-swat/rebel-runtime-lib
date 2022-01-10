import com.ing.rebel.experiment.aws.AwsLib._
import monix.eval.Task

import scala.util.Random
import monix.execution.Scheduler.Implicits.global
import software.amazon.awssdk.services.ecs.model.ContainerInstance

import scala.concurrent.duration._
import scala.collection.immutable
import scala.concurrent.Await
import scala.collection.JavaConverters._
import scala.concurrent.Future


def await[A](t:Task[A]) = {
  Await.result(t.runAsync, 30.seconds)
}


val x = Task.defer {
  val seq: immutable.Seq[Either[Int, Seq[ContainerInstance]]] = (1 to 10).map { i =>
    //  queryAvailableInstances()
    //    .map(cis => cis.apply(Random.nextInt(cis.length)))
    //    .map{ci => {
    println("reserving")
    reserveContainerInstances(1, i.toString)
  }
  val tasks = seq.map(_.fold(
    { i =>
      println(s"not available $i")
      Task.now(())
    },
    { cis =>
      println(s"deserverving ${cis.map(_.containerInstanceArn())}")
      dereserveContainerInstances(cis)
    }
  ))
  Task.sequence(tasks)
}

await(x)

// com.ing.Start.main(Array("clean"))

// Await.result(describeContainerInstances.runAsync, 2.seconds).containerInstances.asScala.map(_.attributes()) foreach println
