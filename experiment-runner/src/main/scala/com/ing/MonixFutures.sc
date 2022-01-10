import monix.eval.Task

import scala.concurrent.{Await, Future}
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration.Duration

val seq = 1 to 3

def stopTasks(taskArns: Iterable[String]): Task[Iterable[String]] =
  Task.gather(taskArns.map { task =>
    println(s"Stopping: $task")
    Task.deferFuture{
      println(s"Stopping in Future $task")
      Future{task.toUpperCase}
    }
  })

def dereserveContainerInstances(instances: Seq[String]): Task[Seq[String]] = {
  Task.gather(instances.map { i =>
    println(s"Clearing $i")
    Task.deferFuture {
      println(s"Trying to clean on $i")
      Future(i.toLowerCase())
    }}
  )
}

val taskArns = Set("aAaAaA", "bBbBbB")
val instances = Seq("Inst1", "Inst2")

def cleanupRunningTasks(taskArns: Set[String], instances: Seq[String]) =
  Task.gather(Seq(stopTasks(taskArns), dereserveContainerInstances(instances))).map(_ => ())


Await.result(cleanupRunningTasks(taskArns, instances).runAsync, Duration.Inf)

//Thread.sleep(2000)

//val ff: Task[Int] = Task.fromFuture(Future {
//  println("Future in fromFuture")
//  42
//})
//
//val df: Task[Int] = Task.deferFuture(Future {
//  println("Future in deferFuture")
//  42
//})
//
//val unitff = ff.map{_ =>
//  print("in unitmap")
//  "UNIT"
//}
//
//val unitdf = df.map{_ =>
//  print("in unitmap")
//  "UNIT"
//}
//
//Await.result(ff.runAsync, Duration.Inf)
//
//Await.result(unitff.runAsync, Duration.Inf)
//
//Await.result(df.runAsync, Duration.Inf)
//
//Await.result(unitdf.runAsync, Duration.Inf)

