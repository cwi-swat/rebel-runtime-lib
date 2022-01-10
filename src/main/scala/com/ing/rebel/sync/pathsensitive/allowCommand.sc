import scala.collection.immutable.ListMap

val input: ListMap[String, Int] = ListMap(("C1", 1), ("C2", 2), ("C3", 3))


//scala.math
//input.values.toSeq.permutations.map(_.toList).toList.filter(_.is)

val commands: Iterable[Int] = input.values

def traces(commands: Seq[Int]): Seq[Seq[Int]] = {
  commands match {
    case Nil => Nil
    case c +: cs =>
      val left: Seq[Seq[Int]] = traces(cs)
      val right: Seq[Seq[Int]] = traces(cs).map(c +: _)
      Seq(c) +: (left ++ right)
  }
}

traces(commands.toSeq).foreach(println)