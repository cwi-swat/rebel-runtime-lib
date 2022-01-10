import java.nio.file.Paths

import com.ing.rebel.experiment.aws.AwsLib._
import monix.execution.Scheduler.Implicits.global


import scala.concurrent.Await
import scala.concurrent.duration._

val f = writeAndGetLogEventsPath("performance", "performance", "ecs/9e62b875-40fb-4095-8cbb-9f0a80ea71df", Paths.get("/tmp/test.log")).runAsync

Await.result(f, 10.seconds)
println("Done")

Thread.sleep(1000)