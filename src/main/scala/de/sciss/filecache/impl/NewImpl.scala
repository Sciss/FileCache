package de.sciss.filecache
package impl

import concurrent._
import de.sciss.serial.{DataOutput, DataInput, ImmutableSerializer}
import java.io.File
import scala.util.control.NonFatal
import collection.immutable.{SortedMap => ISortedMap}
import collection.mutable
import scala.annotation.{elidable, tailrec}
import scala.util.{Failure, Success}

private[filecache] object NewImpl {
  final val COOKIE  = 0x2769f746

  @elidable(elidable.CONFIG) def debug(what: => String) { println(s"<cache> $what") }

//  sealed trait State[+B]
//  case object Released extends State[Nothing]
//  case object Locked   extends State[Nothing]
//  case class  Producing[B](future: Future[B]) extends State[B]

  /** The main cache key entry which is an in-memory representation of an entry (omitting the value).
    *
    * @param key          the key of the entry
    * @param lastModified the age of the entry
    * @param entrySize    the size of the entry file (serialized form of key and value)
    * @param extraSize    the size of associated resources as reported by the configuration's `space` function
    * @param unique       a unique identifier needed to use distiguish two entries with the same age in the ordering
    */
  final class Entry[A](val key: A, val lastModified: Long, val entrySize: Int, val extraSize: Long, val unique: Int) {
    private var _locked = false
    def size = entrySize + extraSize
    def locked = _locked
    // outer must sync
    def lock() {
      debug(s"lock $key")
      if (_locked) throw new IllegalStateException(s"Key $key already locked")
      _locked = true
    }
    // outer must sync
    def unlock() {
      debug(s"unlock $key")
      if (!_locked) throw new IllegalStateException(s"Key $key was not locked")
      _locked = false
    }

    override def toString =
      s"Entry($key, mod = $lastModified, e_sz = $entrySize, rsrc = $extraSize, uid = $unique, locked = $locked)"
  }
}
private[filecache] final class NewImpl[A, B](config: FileCache.Config[A, B])
                                            (implicit keySerializer  : ImmutableSerializer[A],
                                                      valueSerializer: ImmutableSerializer[B])
  extends FileCache[A, B] with Ordering[NewImpl.Entry[A]] {

  import config.{executionContext => exec, _}
  import NewImpl._

  type E = Entry[A]

  override def toString = s"FileCache@${hashCode().toHexString}"

  implicit def executionContext = exec

  private val sync      = new AnyRef
  private val entryMap  = mutable.Map.empty[A, E]
  private val hashKeys  = mutable.Map.empty[Int, A]
  // private val busy      = mutable.Map.empty[A, E]
  private val busySet   = mutable.Set.empty[A]
  private val hasLimit  = capacity.count > 0 || capacity.age.isFinite() || capacity.space > 0L
  private var unique    = 0

  private val prio     = if (hasLimit)
//    mutable.PriorityQueue.empty[E](this)
    mutable.SortedSet.empty[E](this)
  else
    null

  validateFolder()
  scan()

  // highest priority = oldest files. but two different entries must not be yielding zero!
  def compare(x: E, y: E): Int = {
    val byAge = Ordering.Long.compare(x.lastModified, y.lastModified)
    if (byAge != 0) byAge else Ordering.Int.compare(x.unique, y.unique)
  }

  // because keys can have hash collision, there must be a safe way to produce
  // file names even in the case of such a collision. this method uses `hashKeys`
  // to look up the keys belong to a given hash code, beginning with the natural
  // hash code of the provided key. If that same key is found, that is a valid hash code.
  // otherwise, if another key is found, the hash code is incremented by one, and the
  // procedure retried. if _no_ key is found for a hash code, an exception is thrown.
  //
  // outer must sync
  private def findHash(key: A): Int = {
    @tailrec def loop(h: Int): Int =
      hashKeys.get(h) match {
        case Some(`key`)  => h
        case Some(_)      => loop(h + 1)
        case _            => throw new NoSuchElementException(key.toString)
      }

    loop(key.hashCode())
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
  private def nextUnique(): Int = {
    val res = unique
    unique += 1
    res
  }

//  @inline private def touch(f: File) { f.setLastModified(System.currentTimeMillis()) }

  private def updateEntry(oldEntry: E, newEntry: E) {
    debug(s"updateEntry old = $oldEntry; new = $newEntry")
    assert(oldEntry.key == newEntry.key && oldEntry.locked == newEntry.locked)
    sync.synchronized {
      prio     -= oldEntry
      prio     += newEntry
      entryMap += newEntry.key -> newEntry
    }
  }

  def acquire(key: A, producer: => B): Future[B] = acquireWith(key, future(producer))

  def acquireWith(key: A, producer: => Future[B]): Future[B] = sync.synchronized {
    debug(s"acquire $key")
    entryMap.get(key) match {
      case Some(e) =>
        e.lock()  // throws exception if already locked
        val hash      = findHash(key)
        val f         = file(naming(hash))
        val existing  = future {
          val age     = System.currentTimeMillis()
          val tup     = readEntry(f, age = age, uid = e.unique).get  // may throw NoSuchElementException
          f.setLastModified(age)  // existing value was ok. just refresh the file modification date
          tup
        }
        val refresh = existing.recoverWith {
          case _: NoSuchElementException => // didn't accept the existing value
            val fut = producer              // ...have to run producer to get a new one
            fut.map { value =>
              val eNew = writeEntry(f, key, value, uid = e.unique)     // ...and write it to disk
              eNew -> value
            }
        }
        val updated = refresh.map {
          case (eNew, value) => updateEntry(e, eNew); value: B
        }
        updated.recover {
          case NonFatal(t) =>
            debug(s"recover from ${t.getClass}")
            sync.synchronized(e.unlock())
            throw t
        }

      case _ => // not found in all.
        if (busySet.contains(key)) throw new IllegalStateException(s"Entry for $key is already being produced")
        debug(s"busy += $key")
        busySet += key
        val inserted = producer.map { value =>
          val (hash, uid) = sync.synchronized(addHash(key) -> nextUnique())
          val f = file(naming(hash))
          val e = writeEntry(f, key, value, uid = uid)
          sync.synchronized {
            debug(s"entryMap $key -> $e; busySet -= $key")
            entryMap += key -> e
            busySet  -= key
            e.lock()
          }
          value
        }
        inserted.recover {
          case NonFatal(t) => sync.synchronized(busySet -= key); throw t
        }
    }
    // XXX TODO: map resulting future to check for over capacity
  }

  def release(key: A) { sync.synchronized {
    debug(s"release $key")
    if (busySet.contains(key))        throw new IllegalStateException(s"Entry for $key is still being produced")
    if (entryMap.remove(key).isEmpty) throw new IllegalStateException(s"Entry for $key not found")
    // XXX TODO: evict immediately if over capacity
  }}

  private def validateFolder() {
    if (folder.exists()) {
      require(folder.isDirectory && folder.canRead && folder.canWrite, s"Folder $folder is not read/writable")
    } else {
      require(folder.mkdirs(), s"Folder $folder could not be created")
    }
  }

  @inline def file(name: String) = new File(folder, name)

  private def readEntry(f: File, age: Long, uid: Int): Option[(Entry[A], B)] = blocking {
    debug(s"readEntry $f; age = $age; uid = $uid")
    val in = DataInput.open(f)
    try {
      if (in.size >= 4 && (in.readInt() == COOKIE)) {
        val key   = keySerializer  .read(in)
        val value = valueSerializer.read(in)
        try {
          if (accept(value)) {
            val uid0 = if (uid >= 0)  uid else sync.synchronized(nextUnique())
            val m    = if (age >= 0L) age else f.lastModified()
            val n    = in.position
            val r    = space(value)
            val e    = new Entry(key, lastModified = m, entrySize = n, extraSize = r, unique = uid0)
            debug(s"accepted $value; e = $e")
            Some(e -> value)
          } else {
            debug(s"evict $value")
            evict(value)
            None
          }

        } catch {
          case NonFatal(e) =>
            e.printStackTrace()
            None
        }
      } else None

    } catch {
      case NonFatal(_) =>
        in.close()  // close it before trying to delete f
        f.delete()
        None
    } finally {
      in.close()    // closing twice is no prob
    }
  }

  private def writeEntry(f: File, key: A, value: B, uid: Int): E = { blocking {
    debug(s"writeEntry $f; key = $key; value = $value; uid = $uid")
    val out     = DataOutput.open(f)
    var success = false
    try {
      out.writeInt(COOKIE)
      keySerializer  .write(key,   out)
      valueSerializer.write(value, out)
      val n   = out.size
      out.close()
      val m   = f.lastModified()
      val r   = space(value)
      success = true
      new Entry(key, lastModified = m, entrySize = n, extraSize = r, unique = uid)

    } finally {
      if (!success) {
        out.close()
        f.delete()
      }
    }
  }}

  // scan the cache directory and build information about size
  private def scan(): Seq[E] = blocking {
    val a = folder.listFiles(naming)
    if (a == null) {
      Nil // Limit(count = 0, space = 0L, age = Duration.Zero)
    } else {
      for (f <- a; (e, _) <- readEntry(f, age = -1L, uid = -1)) yield e
    }
  }
}