/*
 *  TxnConsumerImpl.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013-2014 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache
package impl

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.concurrent.stm.{TMap, InTxn}

private[filecache] object TxnConsumerImpl {
  final private class Entry[B](val useCount: Int = 1, val future: Future[B]) {
    def inc = new Entry(useCount + 1, future)
    def dec = new Entry(useCount - 1, future)
  }
}
private[filecache] final class TxnConsumerImpl[A, B](producer: TxnProducer[A, B], source: A => Future[B])
  extends TxnConsumer[A, B] {
  import TxnConsumerImpl._

  private type E = Entry[B]

  private val map = TMap.empty[A, E]

  import producer.executionContext

  def acquire(key: A)(implicit tx: InTxn): Future[B] =
    map.get(key).fold {
      val fut = producer.acquireWith(key, source(key))
      val e   = new Entry(future = fut)
      map.put(key, e)
      fut.recover {
        case NonFatal(t) =>
          map.single.remove(key)
          throw t
      }
      fut
    } { e0 =>
      val e1 = e0.inc
      map.put(key, e1)
      e1.future
    }

  def release(key: A)(implicit tx: InTxn): Boolean = {
    val e0    = map.get(key).getOrElse(throw new IllegalStateException(s"Key $key was not in use"))
    val e1    = e0.dec
    val last  = e1.useCount == 0
    if (last) {
      map.remove(key)
      producer.release(key)
    } else {
      map.put(key, e1)
    }
    last
  }

  def usage(implicit tx: InTxn): Limit = producer.usage

  def dispose()(implicit tx: InTxn): Unit = {
    producer.dispose()
    map.clear()
  }
}