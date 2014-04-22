/*
 *  ProducerImpl.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013-2014 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache
package impl

import scala.annotation.{tailrec, elidable}
import scala.concurrent._
import de.sciss.file._
import de.sciss.filecache.impl.ProducerImpl._
import de.sciss.serial.{ImmutableSerializer, DataInput, DataOutput}
import scala.util.control.NonFatal

private[filecache] object ProducerImpl {
  final val COOKIE  = 0x2769F746

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
private[filecache] trait ProducerImpl[A, B] {
  protected type E = ProducerImpl.Entry[A]

  // ---- abstract ----

  protected type Tx

  val config: Config[A, B]

  protected def addUsage(space: Long, count: Int)(implicit tx: Tx): Unit
  protected def getUsage(implicit tx: Tx): Limit

  protected def keySerializer  : ImmutableSerializer[A]
  protected def valueSerializer: ImmutableSerializer[B]

  protected def disposed(implicit tx: Tx): Boolean

  protected def atomic[Z](block: Tx => Z): Z

  protected def fork[Z](body: => Z)(implicit tx: Tx): Future[Z]

  protected def debugImpl(what: => String): Unit

  // ---- data structures ----

  // maps keys to acquired entries
  protected def acquiredMapContains(key: A            )(implicit tx: Tx): Boolean
  protected def acquiredMapPut     (key: A  , value: E)(implicit tx: Tx): Unit
  protected def acquiredMapRemove  (key: A            )(implicit tx: Tx): Option[E]

  // keeps track of keys who currently run sources
  protected def busySetContains    (key: A            )(implicit tx: Tx): Boolean
  protected def busySetAdd         (key: A            )(implicit tx: Tx): Boolean
  protected def busySetRemove      (key: A            )(implicit tx: Tx): Boolean

  // tracks the currently used hash values (of all entries, whether in acquiredMap or releasedMap)
  protected def hashKeysContains   (key: Int          )(implicit tx: Tx): Boolean
  protected def hashKeysPut        (key: Int, value: A)(implicit tx: Tx): Unit
  protected def hashKeysRemove     (key: Int          )(implicit tx: Tx): Option[A]

  // keeps track of entries which may be evicted.
  // these two structures are only maintained in the case
  // that `hasLimit` is `true`.
  protected def releasedMapGet     (key: A            )(implicit tx: Tx): Option[E]
  protected def releasedMapPut     (key: A  , value: E)(implicit tx: Tx): Unit
  protected def releasedMapRemove  (key: A            )(implicit tx: Tx): Option[E]

  protected def releasedQRemove    (idx: Int          )(implicit tx: Tx): Unit
  protected def releasedQHeadOption                    (implicit tx: Tx): Option[E]
  protected def releasedQSize                          (implicit tx: Tx): Int
  protected def releasedQApply     (idx: Int          )(implicit tx: Tx): E
  protected def releasedQUpdate    (idx: Int, value: E)(implicit tx: Tx): Unit
  protected def releasedQInsert    (idx: Int, value: E)(implicit tx: Tx): Unit

  // ---- implemented ----

  override def toString = s"Producer@${hashCode().toHexString}"

  import config.{folder, capacity, space, evict, accept => acceptValue}

  private def extension = "." + config.extension

  implicit final def executionContext: ExecutionContext = config.executionContext

  final protected val hasLimit = capacity.count > 0 || capacity.space > 0L

  @elidable(elidable.CONFIG) final protected def debug(what: => String): Unit = debugImpl(what)

  @inline private def requireAlive()(implicit tx: Tx): Unit =
    if (disposed) throw new IllegalStateException("Producer was already disposed")

  final protected def init()(implicit tx: Tx): Unit = {
    validateFolder()
    if (hasLimit) scan()
  }

  private def validateFolder(): Unit =
    if (folder.exists()) {
      require(folder.isDirectory && folder.canRead && folder.canWrite, s"Folder $folder is not read/writable")
    } else {
      require(folder.mkdirs(), s"Folder $folder could not be created")
    }

  private def addHash(key: A)(implicit tx: Tx): Int = {
    @tailrec def loop(h: Int): Int =
      if (!hashKeysContains(h)) h
      else loop(h + 1)

    val res = loop(key.hashCode())
    hashKeysPut(res, key)
    debug(s"addHash $res -> $key")
    res
  }

  private def removeHash(hash: Int)(implicit tx: Tx): Unit = {
    val key = hashKeysRemove(hash)
    debug(s"removeHash $hash -> $key")
    assert(key.isDefined)
  }

  /*
    Removes an entry from the released set (if contained) and the busy set (if contained),
    and adds it to the acquired set. If there is a cache capacity limit, runs
    `checkFreeSpace()`.
   */
  private def addToAcquired(e: E)(implicit tx: Tx): Unit = {
    debug(s"addToAcquired $e")

    val key      = e.key
    acquiredMapPut(key, e)
    val idx = releasedQIndex(e)
    if (idx >= 0) {
      assert(releasedMapRemove(key).isDefined)
      releasedQRemove(idx)
    }

    debug(s"busySet -= ${e.key}")
    busySetRemove(key)

    // if an entry was added or replaced such that the size increased,
    // check if we need to free space
    if (hasLimit) checkFreeSpace()
  }

  /*
    binary search in releasedQ; outer must sync.

    @return   if >= 0: the position of `entry` in `releasedQ` (i.e. an entry with
              the same modification date is contained in `releasedQ`).
              if negative: `(-ins -1)` where `ins` is the position at which `entry`
              should be inserted into the collection (thus `ins = -(res + 1)`)
  */
  private def releasedQIndex(entry: E)(implicit tx: Tx): Int = {
    var index = 0
    var low   = 0
    var high  = releasedQSize - 1
    while ({
      index = (high + low) >> 1
      low  <= high
    }) {
      val that  = releasedQApply(index)
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

  @inline private def compare(a: E, b: E): Int = {
    val am = a.lastModified
    val bm = b.lastModified
    if (am < bm) -1 else if (am > bm) 1 else {
      val ah = a.hash
      val bh = b.hash
      if (ah < bh) -1 else if (ah > bh) 1 else 0
    }
  }

  // hasLimit must be true
  private def checkFreeSpace()(implicit tx: Tx): Unit = if (isOver) freeSpace()

  private def isOver(implicit tx: Tx): Boolean = !disposed && isOverCapacity

  // hasLimit must be true
  private def isOverCapacity(implicit tx: Tx): Boolean = {
    import config.capacity
    val cnt   = capacity.count
    val curr  = getUsage
    val res   = (cnt >= 0 && curr.count > cnt) || (capacity.space >= 0L && curr.space > capacity.space)
    debug(s"isOverCapacity: $res")
    res
  }

  final protected def acquireImpl(key: A, source: => Future[B])(implicit tx: Tx): Future[B] = {
    requireAlive()
    debug(s"acquire $key")

    if (acquiredMapContains(key)) throw new IllegalStateException(s"Key $key already locked")
    debug(s"busy += $key")
    if (!busySetAdd(key))         throw new IllegalStateException(s"Key $key already being produced")

    val oldHash = releasedMapGet(key).map(_.hash)
    val hash    = oldHash.getOrElse(addHash(key))

    val existing = fork {
      readEntry(hash, update = Some(key)).get  // may throw NoSuchElementException
    }

    val refresh = existing.recoverWith {
      case NonFatal(_) /* if !_disposed */ =>   // _: NoSuchElementException => // didn't accept the existing value
        val fut = source                  // ...have to run source to get a new one
        fut.map { value =>
          val eNew = writeEntry(hash, key, value)     // ...and write it to disk
          eNew -> value
        }
    }
    val registered = refresh.map { case (eNew, value) =>
      atomic { implicit tx =>
        if (!disposed) {
          if (oldHash.isEmpty && hasLimit) {
            addUsage(space = eNew.size, count = 1)
          }
          addToAcquired(eNew)
        }
      }
      value
    }
    registered.recover {
      case NonFatal(t) =>
        debug(s"recover from ${t.getClass}. busySet -= $key")
        atomic { implicit tx =>
          if (!disposed) {
            busySetRemove(key)
            if (oldHash.isEmpty) removeHash(hash)
          }
        }
        throw t
    }
  }

  final protected def releaseImpl(key: A)(implicit tx: Tx): Unit = {
    requireAlive()
    debug(s"release $key")
    if (busySetContains(key))                throw new IllegalStateException(s"Entry for $key is still being produced")
    val e = acquiredMapRemove(key).getOrElse(throw new IllegalStateException(s"Entry for $key not found"))
    debug(s"acquiredMap -= $key -> $e")
    if (hasLimit) {
      addToReleased(e)
    } else {
      removeHash(e.hash)
    }
  }

  /*
  Adds an entry to the released set, and checks if space should be freed.
  */
  private def addToReleased(e: E)(implicit tx: Tx): Unit = {
    debug(s"addToReleased $e")
    // assert(releasedMap.size == releasedQ.size, s"PRE map is ${releasedMap.size} vs. q ${releasedQ.size}")
    releasedMapPut(e.key, e)
    val idx = releasedQIndex(e)
    if (idx >= 0) {
      releasedQUpdate(idx, e)
    } else {
      val ins = -(idx + 1)
      releasedQInsert(ins, e)
    }
    // assert(releasedMap.size == releasedQ.size, s"for $idx map became ${releasedMap.size} vs. q ${releasedQ.size}")
    checkFreeSpace()
  }


  /* Tries to read an entry and associated value from a given file. If that file does not exist,
   * throws an ordinary `FileNotFoundException`. Otherwise deserializes the data (which might
   * raise another exception if the data corrupt). If that is successful, the `accept` function
   * is applied. If the entry is accept, returns it as `Some`, otherwise evicts the entry
   * updating stats if used) and returns `None`.
   *
   * @param  hash    the file's hash to read
   * @param  update  if `true`, the file is 'touched' to be up-to-date. also in the case of eviction,
   *                 the stats are decreased. if `false`, the file is not touched, and eviction does not
   *                 influence the stats.
   */
  private def readEntry(hash: Int, update: Option[A]): Option[(Entry[A], B)] = blocking {
    val name  = hashToName(hash)
    val f     = mkFile(name)
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
          atomic { implicit tx => addUsage(space = -(n + r), count = -1) }
        }
        None
      }
    }
  }

  /* Writes the key-value entry to the given file, and returns an `Entry` for it.
   * The file date is not touched but should obviously correspond to the current
   * system time. It returns the entry thus generated
   */
  private def writeEntry(hash: Int, key: A, value: B): E = { blocking {
    val name  = hashToName(hash)
    val f     = mkFile(name)
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

  private def readKeyValue(f: File): Option[(A, B)] = blocking {
    debug(s"readKeyValue ${f.name}")
    val in = DataInput.open(f)
    try {
      if (in.size >= 4 && (in.readInt() == COOKIE)) {
        val key   = keySerializer  .read(in)
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

  @inline final private def hashToName(hash: Int): String = s"${hash.toHexString}$extension"
  @inline final private def nameToHash(name: String): Int =
    java.lang.Long.parseLong(name.substring(0, name.length - extension.length), 16).toInt // Integer.parseInt fails for > 0x7FFFFFFF !!
  //    Integer.parseInt(name.substring(0, name.length - extension.length), 16)

  @inline final private def mkFile(name: String): File = folder / name

  private def freeSpace()(implicit tx: Tx): Future[Unit] = fork {
    blocking {
      // debug(s"freeSpace - releasedQ size ${releasedQ.size}")
      @tailrec def loop(): Unit = {
        val fo = atomic { implicit tx =>
          if (disposed) None else releasedQHeadOption.map { e =>
            debug(s"freeSpace dequeued $e")
            val hash = e.hash
            assert(releasedMapRemove(e.key).isDefined)
            releasedQRemove(0)
            removeHash(hash)
            addUsage(space = -e.size, count = -1)
            val tmp = java.io.File.createTempFile("evict", "", config.folder)
            val f   = mkFile(hashToName(hash))
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
            val again = atomic { implicit tx => isOver }
            if (again) loop()

          case _ =>
        }
      }

      loop()
    }
  }

  // scan the cache directory and build information about size
  private def scan()(implicit tx: Tx): Future[Unit] = fork {
    blocking {
      val a = config.folder.children { f =>
        val name = f.name
        name.endsWith(extension) && name.substring(0, name.length - extension.length).forall(c =>
          (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
        )
      }
      debug(s"scan finds ${if (a == null) "null" else a.length} files.")
      var i = 0
      var active = true
      while (i < a.size && active) {
        val f = a(i)
        val hash = nameToHash(f.name)
        readEntry(hash, update = None).foreach { case (e, _) =>
          atomic { implicit tx =>
            val key = e.key
            if (!disposed && !acquiredMapContains(key)) {
              debug(s"scan adds $e")
              hashKeysPut(hash, key)
              addUsage(space = e.size, count = 1)
              addToReleased(e)
            }
            active = !disposed
          }
        }
        i += 1
      }
      atomic { implicit tx => checkFreeSpace() }
    }
  }
}