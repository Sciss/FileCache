package de.sciss.filecache

object SizeTest extends App {
  val m = collection.mutable.Map.empty[String, String]
  m += "foo" -> "bar"
  val sz = m.size
  println(sz)
}