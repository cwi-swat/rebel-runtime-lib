import com.paulgoldbaum.influxdbclient._
import com.paulgoldbaum.influxdbclient.InfluxDB

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import ammonite.ops.ImplicitWd._
import ammonite.ops._
import ammonite.terminal.Debug

val influxdb = InfluxDB.connect("localhost", 8086)
val database = influxdb.selectDatabase("telegraf")
val result = database.query("show measurements")


//val tables = Await.result(result, 10.seconds).series.head.records.flatMap(_.allValues)

//tables.foreach { table =>
//  println(table)
//  val result = database.query(s"""select * from "$table"""")
//  println(Await.result(result, 10.seconds).series.head.records.map(_.allValues))
//}

val localDir: String = "/tmp/influx" //"/Users/tim/workspace/account-modelling/ing-codegen-akka/"
//result.map(_.series.head.records.flatMap(_.allValues)).foreach { table =>
//  val commandResult: CommandResult =
//    %%("/usr/local/bin/influx", "-host", "localhost", "-database", "telegraf", "-format", "csv", """-execute""", s"""SELECT * FROM "$table"""")
//  //  println(commandResult)
//  write(Path(s"$localDir/$table.csv"), commandResult.toString())
//}
//Thread.sleep(2000)


val tablesFuture: Future[List[Any]] = result.map(_.series.head.records.flatMap(_.allValues))

val exports: Future[List[Path]] =
  tablesFuture.map(_.map( table => {
    val commandResult: CommandResult =
      %%("/usr/local/bin/influx", "-host", "localhost", "-database", "telegraf", "-format", "csv", """-execute""", s"""SELECT * FROM "$table"""")
    //  println(commandResult)
    val path = Path(s"$localDir/$table.csv")
    write(path, commandResult.toString())
    path
  }
  ))
exports