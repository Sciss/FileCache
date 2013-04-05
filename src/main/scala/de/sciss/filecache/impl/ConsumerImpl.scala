/*
 *  ConsumerImpl.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU General Public License
 *	as published by the Free Software Foundation; either
 *	version 2, june 1991 of the License, or (at your option) any later version.
 *
 *	This software is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *	General Public License for more details.
 *
 *	You should have received a copy of the GNU General Public
 *	License (gpl.txt) along with this software; if not, write to the Free Software
 *	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
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

private[filecache] object ConsumerImpl {
  final class Entry[B](var useCount: Int = 1, val future: Future[B])
}
private[filecache] final class ConsumerImpl[A, B](producer: Producer[A, B], source: A => Future[B])
  extends Consumer[A, B] {
  import ConsumerImpl._

  type E = Entry[B]

  private val sync  = new AnyRef
  private val map   = mutable.Map.empty[A, E]

  import producer.executionContext

  def acquire(key: A): Future[B] = sync.synchronized {
    map.get(key) match {
      case Some(e) =>
        e.useCount += 1
        e.future

      case _ =>
        val fut = producer.acquireWith(key, source(key))
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
  def dispose()    { producer.dispose() }
}