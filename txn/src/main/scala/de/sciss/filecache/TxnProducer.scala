/*
 *  TxnProducer.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013-2020 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache

import de.sciss.filecache.impl.{TxnProducerImpl => Impl}
import de.sciss.serial.ConstFormat

import scala.concurrent.stm.InTxn
import scala.concurrent.{ExecutionContext, Future}

object TxnProducer {
  /** Creates a new cache production instance.
    *
    * @param config           the cache configuration. Typically you pass in the configuration builder which is then
    *                         converted to an immutable `Config` instance.
    * @param keyFormat    the serializer used when writing keys to disk or reading keys from disk
    * @param valueFormat  the serializer used when writing values to disk or reading values from disk
    * @tparam A               the key type
    * @tparam B               the value type
    */
  def apply[A, B](config: Config[A, B])(implicit tx: InTxn,
                                        keyFormat  : ConstFormat[A],
                                        valueFormat: ConstFormat[B]): TxnProducer[A, B] =
    new Impl(config, tx)
}

trait TxnProducer[-A, B] {
  /** Acquires the cache value of a given key.
    * A cache entry, like an exclusive lock, can only be acquired by one instance at a time, therefore if the
    * entry is still locked, this method throws an immediate `IllegalStateException`.
    *
    * If the entry is still found on disk, it will be re-used, given that the configuration's `accept` method
    * returns `true`. If the entry is not found or not accepted, a new value is produced by spawning the source
    * in its own future (using the configuration's `executionContext`).
    *
    * If the source naturally returns a future, use `acquireWith` instead.
    *
    * When the value is not used any more, the caller should invoke `release` to make it possible for the entry
    * to be evicted when over capacity. Only a released entry can be re-acquired.
    *
    * @param key      the key to look up
    * @param source   the source which is only used if the entry was not found or not accepted
    * @return         the future value of the cache entry (this might result in an I/O exception for example)
    */
  def acquire(key: A)(source: => B)(implicit tx: InTxn): Future[B]

  /** Acquires the cache value of a given key. If an exisiting cache entry is found, it will be acquired and 
    * its value will be returned, otherwise nothing happens and the future returns `None`.
    */
  def get(key: A)(implicit tx: InTxn): Future[Option[B]]

  /** Acquires the cache value of a given key.
    * This method is equivalent to `acquire` but takes a source in the form of a future. See `acquire` for
    * more details on the mechanism and requirements of this process.
    *
    * @param key      the key to look up
    * @param source   the source which is only used if the entry was not found or not accepted
    * @return         the future value of the cache entry (this might result in an I/O exception for example)
    */
  def acquireWith(key: A)(source: => Future[B])(implicit tx: InTxn): Future[B]

  /** Release a cache entry. The caller must have acquired the entry for the given key, using
    * `acquire` or `acquireWith`. If the entry is not locked, this method will throw an `IllegalStateException`.
    *
    * Releasing the entry makes it possible to evict it from the cache if the cache capacity is exhausted.
    *
    * @param key  the key to release
    */
  def release(key: A)(implicit tx: InTxn): Unit

  /** The context used by the cache to spawn future computations. This is directly taken from its
    * configuration, and is provided here for clients to easily import it as an implicit value, e.g.
    * to create its own futures.
    */
  implicit def executionContext: ExecutionContext

  /** Reports the current statistics of the cache, which are number of entries, total size and age span. */
  def usage(implicit tx: InTxn): Limit

  /** The configuration used to instantiate the producer. */
  def config: Config[A, B]

  /** Disposes this producer and makes it unavailable for future use.
    * Any attempt to call `acquireWith` or `release` after this invocation results in
    * an `IllegalStateException` being thrown.
    */
  def dispose()(implicit tx: InTxn): Unit

  private[filecache] def activity(implicit tx: InTxn): Future[Unit]
}