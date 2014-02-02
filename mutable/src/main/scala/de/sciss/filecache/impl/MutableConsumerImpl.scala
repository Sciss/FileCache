/*
 *  MutableConsumerImpl.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013-2014 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v3+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache
package impl

import scala.concurrent.Future
import collection.mutable
import scala.util.control.NonFatal

private[filecache] object MutableConsumerImpl {
  final private class Entry[B](var useCount: Int = 1, val future: Future[B])
}
private[filecache] final class MutableConsumerImpl[A, B](producer: MutableProducer[A, B], source: A => Future[B])
  extends MutableConsumer[A, B] {
  import MutableConsumerImpl._

  private type E = Entry[B]

  private val sync  = new AnyRef
  private val map   = mutable.Map.empty[A, E]

  import producer.executionContext

  def acquire(key: A): Future[B] = sync.synchronized {
    map.get(key) match {
      case Some(e) =>
        e.useCount += 1
        e.future

      case _ =>
        val fut = producer.acquireWith(key)(source(key))
        val e   = new Entry(future = fut)
        map += key -> e
        fut.recover {
          case NonFatal(t) =>
            sync.synchronized(map -= key)
            throw t
        }
        fut
    }
  }

  def release(key: A): Boolean = sync.synchronized {
    val e       = map.getOrElse(key, throw new IllegalStateException(s"Key $key was not in use"))
    e.useCount -= 1
    val last    = e.useCount == 0
    if (last) {
      map -= key
      producer.release(key)
    }
    last
  }

  def usage: Limit = producer.usage

  def dispose(): Unit = sync.synchronized {
    producer.dispose()
    map.clear()
  }
}