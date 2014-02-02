/*
 *  TxnConsumer.scala
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

import scala.concurrent.Future
import impl.{TxnConsumerImpl => Impl}
import scala.concurrent.stm.InTxn

/** A `Consumer` simplifies resource management by maintaining a use count for each cached value.
  * Furthermore, it combines a producer with a production function.
  */
object TxnConsumer {
  /** Creates a new consumer from a given producer and production function.
    *
    * @param producer the cache producing instance
    * @param source   a function which will create the value for a given key whenever the resource is acquired and
    *                 no valid cached value is found.
    * @tparam A       the key type
    * @tparam B       the value type
    */
  def apply[A, B](producer: TxnProducer[A, B])(source: A => Future[B]): TxnConsumer[A, B] = new Impl(producer, source)
}

/** A `Consumer` simplifies resource management by maintaining a use count for each cached value.
  * Furthermore, it combines a producer with a production function.
  *
  * @tparam A the key type
  * @tparam B the value type
  */
trait TxnConsumer[-A, +B] {
  // def producer: Producer[A, B]

  /** Logically acquires a resource. If this is the first time `acquire` is called, this may
    * call `acquire` on the underlying producer. Otherwise, it will simply re-use the already
    * acquired resource and internally increment a use counter.
    *
    * @param key  the resource key
    * @return     the resource value, possibly an uncompleted future if the value was not cached
    */
  def acquire(key: A)(implicit tx: InTxn): Future[B]

  /** Logically releases a resources. This internally decrements a use counter. If the counter
    * reaches zero, it actually calls `release` on the underlying producer.
    *
    * @param key  the resource key
    * @return     `true` if the resource was actually released from the producer.
    */
  def release(key: A)(implicit tx: InTxn): Boolean

  /** Reports the cache usage of the underlying producer.
    *
    * @see [[TxnProducer#usage]]
    */
  def usage(implicit tx: InTxn): Limit

  /** Disposes the underlying producer (and thus invalidates this consumer as well).
    *
    * @see [[TxnProducer#dispose]]
    */
  def dispose()(implicit tx: InTxn): Unit
}