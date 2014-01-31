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

import concurrent._
import de.sciss.serial.ImmutableSerializer
import de.sciss.file._
import scala.util.control.NonFatal
import collection.mutable
import scala.annotation.tailrec

private[filecache] final class MutableProducerImpl[A, B](val config: Config[A, B])
                                                 (implicit protected val keySerializer  : ImmutableSerializer[A],
                                                           protected val valueSerializer: ImmutableSerializer[B])
  extends Producer[A, B] with ProducerImpl[A, B] {

  import ProducerImpl.debug

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

  @volatile private var _disposed = false

  // -------------------- constructor --------------------

  validateFolder()
  if (hasLimit) scan()

  // -------------------- public --------------------

  def usage: Limit = sync.synchronized {
    Limit(count = totalCount, space = totalSpace)
  }

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

  def acquire(key: A, source: => B): Future[B] = acquireWith(key, future(source))

  @inline private def requireAlive(): Unit =
    if (_disposed) throw new IllegalStateException("Producer was already disposed")

  def acquireWith(key: A, source: => Future[B]): Future[B] = sync.synchronized {
    requireAlive()
    debug(s"acquire $key")

    if (acquiredMap.contains(key)) throw new IllegalStateException(s"Key $key already locked")
    debug(s"busy += $key")
    if (!busySet.add(key))         throw new IllegalStateException(s"Key $key already being produced")

    val oldHash = releasedMap.get(key).map(_.hash)
    val hash    = oldHash.getOrElse(addHash(key))

    val existing = fork {
      readEntry(hash, update = Some(key)).get  // may throw NoSuchElementException
    }

    val refresh = existing.recoverWith {
      case NonFatal(_) if !_disposed =>   // _: NoSuchElementException => // didn't accept the existing value
        val fut = source                  // ...have to run source to get a new one
        fut.map { value =>
          val eNew = writeEntry(hash, key, value)     // ...and write it to disk
          eNew -> value
        }
    }
    val registered = refresh.map { case (eNew, value) =>
      sync.synchronized {
        if (!_disposed) {
          if (oldHash.isEmpty && hasLimit) {
            totalSpace += eNew.size
            totalCount += 1
          }
          addToAcquired(eNew)
        }
      }
      value
    }
    registered.recover {
      case NonFatal(t) =>
        debug(s"recover from ${t.getClass}. busySet -= $key")
        sync.synchronized {
          if (!_disposed) {
            busySet -= key
            if (oldHash.isEmpty) removeHash(hash)
          }
        }
        throw t
    }
  }

  def release(key: A): Unit = sync.synchronized {
    requireAlive()
    debug(s"release $key")
    if (busySet.contains(key))                throw new IllegalStateException(s"Entry for $key is still being produced")
    val e = acquiredMap.remove(key).getOrElse(throw new IllegalStateException(s"Entry for $key not found"))
    debug(s"acquiredMap -= $key -> $e")
    if (hasLimit) {
      addToReleased(e)
    } else {
      removeHash(e.hash)
    }
  }

  // -------------------- private --------------------

  @inline private def compare(a: E, b: E): Int = {
    val am = a.lastModified
    val bm = b.lastModified
    if (am < bm) -1 else if (am > bm) 1 else {
      val ah = a.hash
      val bh = b.hash
      if (ah < bh) -1 else if (ah > bh) 1 else 0
    }
  }

  /*
      binary search in releasedQ; outer must sync.

      @return   if >= 0: the position of `entry` in `releasedQ` (i.e. an entry with
                the same modification date is contained in `releasedQ`).
                if negative: `(-ins -1)` where `ins` is the position at which `entry`
                should be inserted into the collection (thus `ins = -(res + 1)`)
   */
  private def releasedQIndex(entry: E): Int = {
    var index = 0
    var low   = 0
    var high  = releasedQ.size - 1
    while ({
      index = (high + low) >> 1
      low  <= high
    }) {
      val that  = releasedQ(index)
      val cmp   = compare(entry, that)
      if (cmp == 0) return index
      if (cmp > 0) {    // entry is greater than found element, thus continue in upper half
        low = index + 1
      } else {          // entry is less    than found element, thus continue in lower half
        high = index - 1
      }
    }
    -low - 1
  }

  private def fork[T](body: => T): Future[T] = {
    val res = future(body)
    sync.synchronized {
      if (!_disposed) futures += res
    }
    res.onComplete(_ => sync.synchronized(futures -= res))
    res
  }

  // outer must sync
  private def addHash(key: A): Int = {
    @tailrec def loop(h: Int): Int =
      if (!hashKeys.contains(h)) h
      else loop(h + 1)

    val res = loop(key.hashCode())
    hashKeys += res -> key
    debug(s"addHash $res -> $key")
    res
  }

  // outer must sync
  private def removeHash(hash: Int): Unit = {
    val key = hashKeys.remove(hash)
    debug(s"removeHash $hash -> $key")
    assert(key.isDefined)
  }

  /*
    Removes an entry from the released set (if contained) and the busy set (if contained),
    and adds it to the acquired set. If there is a cache capacity limit, runs
    `checkFreeSpace()`.

    outer must sync
   */
  private def addToAcquired(e: E): Unit = {
    debug(s"addToAcquired $e")

    val key      = e.key
    acquiredMap += key -> e
    val idx = releasedQIndex(e)
    if (idx >= 0) {
      assert(releasedMap.remove(key).isDefined)
      releasedQ.remove(idx)
    }

    debug(s"busySet -= ${e.key}")
    busySet -= key

    // if an entry was added or replaced such that the size increased,
    // check if we need to free space
    if (hasLimit) checkFreeSpace()
  }

  // outer must sync; hasLimit must be true
  private def checkFreeSpace(): Unit = if (isOverCapacity) freeSpace()

  // outer must sync; hasLimit must be true
  private def isOverCapacity: Boolean = {
    import config.capacity
    val cnt = capacity.count
    val res = (cnt >= 0 && totalCount > cnt) || (capacity.space >= 0L && totalSpace > capacity.space)
    debug(s"isOverCapacity: $res")
    res
  }

  protected def addUsage(space: Long, count: Int): Unit = sync.synchronized {
    totalSpace += space
    totalCount += count
  }

  /*
    Adds an entry to the released set, and checks if space should be freed.
    outer must sync
   */
  private def addToReleased(e: E): Unit = {
    debug(s"addToReleased $e")
    // assert(releasedMap.size == releasedQ.size, s"PRE map is ${releasedMap.size} vs. q ${releasedQ.size}")
    releasedMap += e.key -> e
    val idx = releasedQIndex(e)
    if (idx >= 0) {
      releasedQ.update(idx, e)
    } else {
      val ins = -(idx + 1)
      releasedQ.insert(ins, e)
    }
    // assert(releasedMap.size == releasedQ.size, s"for $idx map became ${releasedMap.size} vs. q ${releasedQ.size}")
    checkFreeSpace()
  }

  // scan the cache directory and build information about size
  private def scan(): Future[Unit] = fork {
    blocking {
      val a = config.folder.listFiles(this)
      debug(s"scan finds ${if (a == null) "null" else a.length} files.")
      if (a != null) {
        var i = 0
        while (i < a.length && !_disposed) {
          val f = a(i)
          val hash = nameToHash(f.name)
          readEntry(hash, update = None).foreach { case (e, _) =>
            sync.synchronized {
              val key = e.key
              if (!_disposed && !acquiredMap.contains(key)) {
                debug(s"scan adds $e")
                hashKeys   += hash -> key
                totalSpace += e.size
                totalCount += 1
                addToReleased(e)
              }
            }
          }
          i += 1
        }
        if (!_disposed && hasLimit && sync.synchronized(isOverCapacity)) freeSpace()
      }
    }
  }

  private def freeSpace(): Future[Unit] = fork {
    blocking {
      debug(s"freeSpace - releasedQ size ${releasedQ.size}")
      @tailrec def loop(): Unit = {
        val fo = sync.synchronized {
          if (_disposed) None else releasedQ.headOption.map { e =>
            debug(s"freeSpace dequeued $e")
            val hash = e.hash
            assert(releasedMap.remove(e.key).isDefined)
            releasedQ.remove(0)
            removeHash(hash)
            totalSpace  -= e.size
            totalCount  -= 1
            val tmp = java.io.File.createTempFile("evict", "", config.folder)
            val f   = file(hashToName(hash))
            f.renameTo(tmp)
            tmp
          }
        }

        // cannot use `fo.foreach` because of `@tailrec`!
        fo match {
          case Some(f) =>
            val opt = readKeyValue(f)
            f.delete()
            opt.foreach { case (key, value) =>
              debug(s"evict $value")
              config.evict(key, value)
            }
            if (!_disposed && sync.synchronized(isOverCapacity)) loop()

          case _ =>
        }
      }

      loop()
    }
  }
}