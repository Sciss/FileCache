/*
 *  MutableProducerImpl.scala
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

import de.sciss.serial.ImmutableSerializer
import collection.mutable
import scala.concurrent.Future

private[filecache] final class MutableProducerImpl[A, B](val config: Config[A, B])
                                                 (implicit protected val keySerializer  : ImmutableSerializer[A],
                                                           protected val valueSerializer: ImmutableSerializer[B])
  extends MutableProducer[A, B] with ProducerImpl[A, B] {

  protected type Tx = Unit

  // used to synchronize mutable updates to the state of this cache manager
  private val sync        = new AnyRef

  // maps keys to acquired entries
  private val acquiredMap = mutable.Map.empty[A, E]

  // tracks the currently used hash values (of all entries, whether in acquiredMap or releasedMap)
  private val hashKeys    = mutable.Map.empty[Int, A]

  // keeps track of keys who currently run sources
  private val busySet     = mutable.Set.empty[A]

  // keeps track of entries which may be evicted.
  // these two structures are only maintained in the case
  // that `hasLimit` is `true`.
  private val releasedMap = mutable.Map.empty[A, E]
  private val releasedQ   = mutable.Buffer.empty[E]

  private var totalSpace  = 0L
  private var totalCount  = 0

  // keeps track of open futures
  private val futures     = mutable.Set.empty[Future[Any]]

  private var _disposed   = false

  // -------------------- constructor --------------------

  init()()

  // -------------------- public --------------------

  def usage: Limit = sync.synchronized { getUsage() }

  def activity: Future[Unit] = Future.fold(sync.synchronized(futures.toList))(())((_, _) => ())

  def dispose(): Unit = sync.synchronized {
    _disposed = true
    acquiredMap .clear()
    hashKeys    .clear()
    busySet     .clear()
    releasedMap .clear()
    releasedQ   .clear()
    futures     .clear()
    totalSpace = 0L
    totalCount = 0
  }

  def acquire(key: A, source: => B): Future[B] = acquireWith(key, Future(source))

  def acquireWith(key: A, source: => Future[B]): Future[B] = sync.synchronized {
    acquireImpl(key, source)()
  }

  def release(key: A): Unit = sync.synchronized {
    releaseImpl(key)()
  }

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

  protected def releasedQRemove    (idx: Int          )(implicit tx: Tx): Unit      = releasedQ.remove(idx)
  protected def releasedQHeadOption                    (implicit tx: Tx): Option[E] = releasedQ.headOption
  protected def releasedQSize                          (implicit tx: Tx): Int       = releasedQ.size
  protected def releasedQApply     (idx: Int          )(implicit tx: Tx): E         = releasedQ(idx)
  protected def releasedQUpdate    (idx: Int, value: E)(implicit tx: Tx): Unit      = releasedQ.update(idx, value)
  protected def releasedQInsert    (idx: Int, value: E)(implicit tx: Tx): Unit      = releasedQ.insert(idx, value)

  // -------------------- protected --------------------

  protected def fork[T](body: => T)(implicit tx: Tx): Future[T] = {
    val res = Future(body)
    sync.synchronized {
      if (!_disposed) futures += res
    }
    res.onComplete(_ => sync.synchronized(futures -= res))
    res
  }

  protected def addUsage(space: Long, count: Int)(implicit tx: Tx): Unit = {
    totalSpace += space
    totalCount += count
  }

  protected def getUsage(implicit tx: Tx): Limit = Limit(space = totalSpace, count = totalCount)

  protected def disposed(implicit tx: Tx): Boolean = _disposed

  protected def atomic[Z](block: Tx => Z): Z = block()

  protected def debugImpl(what: => String): Unit = println(s"<cache> $what")
}