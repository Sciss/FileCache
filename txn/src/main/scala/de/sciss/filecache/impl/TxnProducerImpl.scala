/*
 *  TxnProducerImpl.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013-2017 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache
package impl

import de.sciss.serial.ImmutableSerializer

import scala.concurrent.stm.{InTxn, Ref, TMap, TSet, Txn, TxnExecutor}
import scala.concurrent.{Future, Promise}

private[filecache] final class TxnProducerImpl[A, B](val config: Config[A, B], tx0: InTxn)
                                                    (implicit protected val keySerializer  : ImmutableSerializer[A],
                                                              protected val valueSerializer: ImmutableSerializer[B])
  extends TxnProducer[A, B] with ProducerImpl[A, B] {

  protected type Tx = InTxn

  // maps keys to acquired entries
  private val acquiredMap = TMap.empty[A, E]

  // tracks the currently used hash values (of all entries, whether in acquiredMap or releasedMap)
  private val hashKeys    = TMap.empty[Int, A]

  // keeps track of keys who currently run sources
  private val busySet     = TSet.empty[A]

  // keeps track of entries which may be evicted.
  // these two structures are only maintained in the case
  // that `hasLimit` is `true`.
  private val releasedMap = TMap.empty[A, E]
  private val releasedQ   = Ref(Vector.empty[E])

  private val _usage      = Ref(Limit(space = 0L, count = 0))

  // keeps track of open futures
  private val futures     = TSet.empty[Future[Any]]

  private val _disposed   = Ref(initialValue = false)

  // -------------------- constructor --------------------

  init()(tx0)

  // -------------------- public --------------------

  def usage(implicit tx: InTxn): Limit = getUsage

  def activity(implicit tx: InTxn): Future[Unit] = Future.fold(futures.toList)(())((_, _) => ())

  def dispose()(implicit tx: InTxn): Unit = {
    _disposed() = true
    acquiredMap .clear()
    hashKeys    .clear()
    busySet     .clear()
    releasedMap .clear()
    releasedQ   () = Vector.empty
    futures     .clear()
    _usage.set(Limit(space = 0L, count = 0))
  }

  def acquire(key: A)(source: => B)(implicit tx: InTxn): Future[B] = acquireWith(key)(Future(source))

  def acquireWith(key: A)(source: => Future[B])(implicit tx: InTxn): Future[B] = acquireImpl(key, source)

  def release(key: A)(implicit tx: InTxn): Unit = releaseImpl(key)

  // ---- data structures ----

  protected def acquiredMapContains(key: A            )(implicit tx: Tx): Boolean   = acquiredMap.contains(key)
  protected def acquiredMapPut     (key: A  , value: E)(implicit tx: Tx): Unit      = acquiredMap.put(key, value)
  protected def acquiredMapRemove  (key: A            )(implicit tx: Tx): Option[E] = acquiredMap.remove(key)

  protected def busySetContains    (key: A            )(implicit tx: Tx): Boolean   = busySet.contains(key)
  protected def busySetAdd         (key: A            )(implicit tx: Tx): Boolean   = busySet.add(key)
  protected def busySetRemove      (key: A            )(implicit tx: Tx): Boolean   = busySet.remove(key)

  protected def releasedMapGet     (key: A            )(implicit tx: Tx): Option[E] = releasedMap.get(key)
  protected def releasedMapPut     (key: A  , value: E)(implicit tx: Tx): Unit      = releasedMap.put(key, value)
  protected def releasedMapRemove  (key: A            )(implicit tx: Tx): Option[E] = releasedMap.remove(key)

  protected def hashKeysContains   (key: Int          )(implicit tx: Tx): Boolean   = hashKeys.contains(key)
  protected def hashKeysPut        (key: Int, value: A)(implicit tx: Tx): Unit      = hashKeys.put(key, value)
  protected def hashKeysRemove     (key: Int          )(implicit tx: Tx): Option[A] = hashKeys.remove(key)

  protected def releasedQRemove    (idx: Int          )(implicit tx: Tx): Unit      = releasedQ.transform(_.patch(idx, Nil, 1))
  protected def releasedQHeadOption                    (implicit tx: Tx): Option[E] = releasedQ().headOption
  protected def releasedQSize                          (implicit tx: Tx): Int       = releasedQ().size
  protected def releasedQApply     (idx: Int          )(implicit tx: Tx): E         = releasedQ().apply(idx)
  protected def releasedQUpdate    (idx: Int, value: E)(implicit tx: Tx): Unit      = releasedQ.transform(_.updated(idx, value))
  protected def releasedQInsert    (idx: Int, value: E)(implicit tx: Tx): Unit      = releasedQ.transform(_.patch(idx, value :: Nil, 0))

  // -------------------- protected --------------------

  protected def fork[T](body: => T)(implicit tx: Tx): Future[T] = {
    val p   = Promise[T]()
    val res = p.future
    Txn.afterCommit { _ =>
      p completeWith Future(body)
    }
    if (!_disposed()) futures += res
    res.onComplete { _ =>
      futures.single -= res
    }
    res
  }

  protected def addUsage(space: Long, count: Int)(implicit tx: Tx): Unit =
    _usage.transform { old =>
      Limit(space = old.space + space, count = old.count + count)
    }

  protected def getUsage(implicit tx: Tx): Limit   = _usage()
  protected def disposed(implicit tx: Tx): Boolean = _disposed()

  protected def atomic[Z](block: Tx => Z): Z = TxnExecutor.defaultAtomic(block)

  protected def debugImpl(what: => String): Unit = {
    def doPrint(): Unit = println(s"<cache> $what")

    Txn.findCurrent.fold(doPrint()) { implicit tx =>
      Txn.afterCommit(_ => doPrint())
    }
  }
}