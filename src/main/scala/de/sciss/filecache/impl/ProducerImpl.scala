/*
 *  ProducerImpl.scala
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

import concurrent._
import de.sciss.serial.{DataOutput, DataInput, ImmutableSerializer}
import java.io.{FilenameFilter, File}
import scala.util.control.NonFatal
import collection.mutable
import scala.annotation.{elidable, tailrec}

private[filecache] object ProducerImpl {
  final val COOKIE  = 0x2769f746

  @elidable(elidable.CONFIG) def debug(what: => String) { println(s"<cache> $what") }

  /** The main cache key entry which is an in-memory representation of an entry (omitting the value).
    *
    * @param key          the key of the entry
    * @param lastModified the age of the entry
    * @param entrySize    the size of the entry file (serialized form of key and value)
    * @param extraSize    the size of associated resources as reported by the configuration's `space` function
    */
  final case class Entry[A](key: A, hash: Int, lastModified: Long, entrySize: Int, extraSize: Long) {
    def size = entrySize + extraSize

    override def toString =
      s"Entry($key, hash = $hash, mod = ${formatAge(lastModified)}, e_sz = $entrySize, rsrc = $extraSize)"
  }

  private def formatAge(n: Long) = new java.util.Date(n).toString
}
private[filecache] final class ProducerImpl[A, B](val config: Producer.Config[A, B])
                                                 (implicit keySerializer  : ImmutableSerializer[A],
                                                           valueSerializer: ImmutableSerializer[B])
  extends Producer[A, B] with FilenameFilter {

  import config.{executionContext => exec, accept => acceptValue, extension => _, _}
  import ProducerImpl._

  type E = Entry[A]

  override def toString = s"Producer@${hashCode().toHexString}"

  implicit def executionContext = exec

  // used to synchronize mutable updates to the state of this cache manager
  private val sync        = new AnyRef

  // maps keys to acquired entries
  private val acquiredMap = mutable.Map.empty[A, E]

  // tracks the currently used hash values (of all entries, whether in acquiredMap or releasedMap)
  private val hashKeys    = mutable.Map.empty[Int, A]

  // keeps track of keys who currently run sources
  private val busySet     = mutable.Set.empty[A]

  private val extension   = "." + config.extension

  // keeps track of entries which may be evicted.
  // these two structures are only maintained in the case
  // that `hasLimit` is `true`.
  private val releasedMap = mutable.Map.empty[A, E]
  private val releasedQ   = mutable.Buffer.empty[E]
  private val hasLimit    = capacity.count > 0 || /* capacity.age.isFinite() || */ capacity.space > 0L

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

  def dispose() {
    _disposed = true
  }

  def acquire(key: A, source: => B): Future[B] = acquireWith(key, future(source))

  // TODO: checking against _disposed in multiple places?

  def acquireWith(key: A, source: => Future[B]): Future[B] = sync.synchronized {
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
      case NonFatal(_) if (!_disposed) => // _: NoSuchElementException => // didn't accept the existing value
        val fut = source                  // ...have to run source to get a new one
        fut.map { value =>
          val eNew = writeEntry(hash, key, value)     // ...and write it to disk
          eNew -> value
        }
    }
    val registered = refresh.map {
      case (eNew, value) => sync.synchronized {
        if (oldHash.isEmpty && hasLimit) {
          totalSpace += eNew.size
          totalCount += 1
        }
        addToAcquired(eNew)
      }
      value
    }
    registered.recover {
      case NonFatal(t) =>
        debug(s"recover from ${t.getClass}. busySet -= $key")
        sync.synchronized {
          busySet -= key
          if (oldHash.isEmpty) removeHash(hash)
        }
        throw t
    }
  }

  def release(key: A) { sync.synchronized {
    debug(s"release $key")
    if (busySet.contains(key))                throw new IllegalStateException(s"Entry for $key is still being produced")
    val e = acquiredMap.remove(key).getOrElse(throw new IllegalStateException(s"Entry for $key not found"))
    debug(s"acquiredMap -= $key -> $e")
    if (hasLimit) {
      addToReleased(e)
    } else {
      removeHash(e.hash)
    }
  }}

  // -------------------- FilenameFilter --------------------

  def accept(dir: File, name: String): Boolean =
    name.endsWith(extension) && name.substring(0, name.length - extension.length).forall(c =>
      (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
    )

  // -------------------- private --------------------

  @inline private def hashToName(hash: Int): String = s"${hash.toHexString}$extension"
  @inline private def nameToHash(name: String): Int =
    Integer.parseInt(name.substring(0, name.length - extension.length), 16)

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
    sync.synchronized(futures += res)
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
  private def removeHash(hash: Int) {
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
  private def addToAcquired(e: E) {
    debug(s"addToAcquired $e")

    val key      = e.key
    acquiredMap += key -> e
    val idx = releasedQIndex(e)
    if (idx >= 0) {
      assert(releasedMap.remove(key).isDefined)
      releasedQ.remove(idx)
    }

    debug(s"busySet -= ${e.key}")
    busySet  -= key

    // if an entry was added or replaced such that the size increased,
    // check if we need to free space
    if (hasLimit) checkFreeSpace()
  }

  // outer must sync; hasLimit must be true
  private def checkFreeSpace() {
    if (isOverCapacity) freeSpace()
  }

  // outer must sync; hasLimit must be true
  private def isOverCapacity: Boolean = {
    val cnt = capacity.count
    val res = (cnt >= 0 && totalCount > cnt) || (capacity.space >= 0L && totalSpace > capacity.space)
    debug(s"isOverCapacity: $res")
    res
  }

  private def validateFolder() {
    if (folder.exists()) {
      require(folder.isDirectory && folder.canRead && folder.canWrite, s"Folder $folder is not read/writable")
    } else {
      require(folder.mkdirs(), s"Folder $folder could not be created")
    }
  }

  @inline private def file(name: String) = new File(folder, name)

  private def readKeyValue(f: File): Option[(A, B)] = blocking {
    debug(s"readKeyValue ${f.getName}")
    val in = DataInput.open(f)
    try {
      if (in.size >= 4 && (in.readInt() == COOKIE)) {
        val key   = keySerializer.read(in)
        val value = valueSerializer.read(in)
        Some(key -> value)
      } else {
        None
      }
    } catch {
      case NonFatal(_) =>
        in.close()  // close it before trying to delete f
        f.delete()
        None
    } finally {
      in.close()    // closing twice is no prob
    }
  }

  /*
    Tries to read an entry and associated value from a given file. If that file does not exist,
    throws an ordinary `FileNotFoundException`. Otherwise deserialises the data (which might
    raise another exception if the data corrupt). If that is successful, the `accept` function
    is applied. If the entry is accept, returns it as `Some`, otherwise evicts the entry
    updating stats if used) and returns `None`.

    @param  f       the file to read
    @param  update  if `true`, the file is 'touched' to be up-to-date. also in the case of eviction,
                    the stats are decreased. if `false`, the file is not touched, and eviction does not
                    influence the stats.
   */
  private def readEntry(hash: Int, update: Option[A]): Option[(Entry[A], B)] = blocking {
    val name  = hashToName(hash)
    val f     = file(name)
    debug(s"readEntry $name; update = $update")
    readKeyValue(f).flatMap { case (key, value) =>
      val n   = f.length().toInt // in.position
      val r   = space(key, value)
      if ((update.isEmpty || update.get == key) && acceptValue(key, value)) {
        if (update.isDefined) f.setLastModified(System.currentTimeMillis())
        val m   = f.lastModified()
        val e   = Entry(key, hash = hash, lastModified = m, entrySize = n, extraSize = r)
        debug(s"accepted $value; e = $e")
        Some(e -> value)
      } else {
        debug(s"evict $value")
        evict(key, value)
        if (update.isDefined && hasLimit) {
          totalSpace -= n + r
          totalCount -= 1
        }
        None
      }
    }
  }

  /*
    Writes the key-value entry to the given file, and returns an `Entry` for it.
    The file date is not touched but should obviously correspond to the current
    system time. It returns the entry thus generated
   */
  private def writeEntry(hash: Int, key: A, value: B): E = { blocking {
    val name  = hashToName(hash)
    val f     = file(name)
    debug(s"writeEntry $name; key = $key; value = $value")
    val out     = DataOutput.open(f)
    var success = false
    try {
      out.writeInt(COOKIE)
      keySerializer  .write(key,   out)
      valueSerializer.write(value, out)
      val n   = out.size
      out.close()
      val m   = f.lastModified()
      val r   = space(key, value)
      success = true
      Entry(key, hash = hash, lastModified = m, entrySize = n, extraSize = r)

    } finally {
      if (!success) {
        out.close()
        f.delete()
      }
    }
  }}

  /*
    Adds an entry to the released set, and checks if space should be freed.
    outer must sync
   */
  private def addToReleased(e: E) {
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
      val a = folder.listFiles(this)
      debug(s"scan finds ${if (a == null) "null" else a.length} files.")
      if (a != null) {
        var i = 0
        while (i < a.length && !_disposed) {
          val f = a(i)
          val hash = nameToHash(f.getName)
          readEntry(hash, update = None).foreach { case (e, _) =>
            sync.synchronized {
              val key = e.key
              if (!acquiredMap.contains(key)) {
                debug(s"scan adds $e")
                hashKeys  += hash -> key
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
    //    sync.synchronized(releasedMap.foreach { case (_, e) =>
    //      println(s"---freeSpace $e")
    //    })
    blocking {
      debug(s"freeSpace - releasedQ size ${releasedQ.size}")
      @tailrec def loop() {
        val fo = sync.synchronized {
          releasedQ.headOption.map { e =>
            debug(s"freeSpace dequeued $e")
            val hash = e.hash
            assert(releasedMap.remove(e.key).isDefined)
            releasedQ.remove(0)
            removeHash(hash)
            totalSpace  -= e.size
            totalCount  -= 1
            val tmp = File.createTempFile("evict", "", folder)
            val f   = file(hashToName(hash))
            f.renameTo(tmp)
            tmp
          }
        }

        fo match {
          case Some(f) =>
            val opt = readKeyValue(f)
            f.delete()
            opt.foreach { case (key, value) =>
              debug(s"evict $value")
              evict(key, value)
            }
            if (!_disposed && sync.synchronized(isOverCapacity)) loop()

          case _ =>
        }
      }

      loop()
    }
  }
}