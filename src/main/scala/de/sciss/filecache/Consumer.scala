package de.sciss.filecache

import scala.concurrent.Future

object Consumer {
  def apply[A, B](producer: Producer[A, B]): Consumer[A, B] = ???
}
trait Consumer[A, B] {
  def producer: Producer[A, B]

  def acquire(key: A): Future[B]
  def release(key: A): Unit
}