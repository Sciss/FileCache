package de.sciss.filecache
package impl

import concurrent._
import de.sciss.serial.{DataOutput, DataInput, ImmutableSerializer}
import java.io.File
import scala.util.control.NonFatal
import collection.immutable.{SortedMap => ISortedMap}
import collection.mutable
import scala.annotation.tailrec
import scala.util.{Failure, Success}

private[filecache] object NewImpl {
  final val COOKIE  = 0x2769f746

//  sealed trait State[+B]
//  case object Released extends State[Nothing]
//  case object Locked   extends State[Nothing]
//  case class  Producing[B](future: Future[B]) extends State[B]

  final class Entry[A](val key: A, val lastModified: Long, val entrySize: Long, val extraSize: Long, val unique: Int) {
    private var _locked = false
    def size = entrySize + extraSize
    def locked = _locked
    // outer must sync
    def lock() {
      if (_locked) throw new IllegalStateException(s"Key $key already locked")
      _locked = true
    }
    // outer must sync
    def unlock() {
      if (!_locked) throw new IllegalStateException(s"Key $key was not locked")
      _locked = false
    }
  }
}
private[filecache] final class NewImpl[A, B](config: FileCache.Config[B])
                                            (implicit keySerializer  : ImmutableSerializer[A],
                                                      valueSerializer: ImmutableSerializer[B])
  extends FileCache[A, B] with Ordering[NewImpl.Entry[A]] {

  import config._
  import NewImpl._

  type E = Entry[A]

  override def toString = s"FileCache@${hashCode().toHexString}"

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
    res
  }

  // outer must sync
  private def nextUnique(): Int = {
    val res = unique
    unique += 1
    res
  }

  @inline private def touch(f: File) { f.setLastModified(System.currentTimeMillis()) }

  private def updateAge(key: A, f: File) {
    sync.synchronized {
      ???
      // TODO: remove from prio, update lastModified variable, add to prio
    }
  }

  def acquire(key: A, producer: => Future[B]): Future[B] = sync.synchronized {
    entryMap.get(key) match {
      case Some(e) =>
        e.lock()  // throws exception if already locked
        val hash      = findHash(key)
        val f         = file(naming(hash))
        val existing  = future {
          val value = readEntry(f).get._2 // may throw NoSuchElementException
          touch(f)                        // existing value was ok. just refresh the file modification date
          value
        }
        val refresh   = existing.recoverWith {
          case _: NoSuchElementException => // didn't accept the existing value
            val fut = producer              // ...have to run producer to get a new one
            fut.map { value =>
              writeEntry(f, key, value)     // ...and write it to disk
              value
            }
        }
        refresh.andThen {
          case Success(value) => updateAge(key, f); value
          case Failure(t)     => sync.synchronized(e.unlock()); throw t
        }

//        refresh.recover {
//          case NonFatal(t) => sync.synchronized(e.locked = false); throw t
//        }

      case _ => // not found in all.
        if (busySet.contains(key)) throw new IllegalStateException(s"Entry for $key is already being produced")
        busySet += key
        val inserted = producer.map { value =>
          val (hash, uid) = sync.synchronized(addHash(key) -> nextUnique())
          val f = file(naming(hash))
          writeEntry(f, key, value)
          val e = new Entry(key, lastModified = f.lastModified(), entrySize = f.length(),
            extraSize = space(value), unique = uid)
          sync.synchronized {
            entryMap += key -> e
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
    if (entryMap.remove(key).isEmpty) throw new IllegalStateException(s"Entry for $key not found")
    // XXX TODO: evict immediate if over capacity
  }}

  private def validateFolder() {
    if (folder.exists()) {
      require(folder.isDirectory && folder.canRead && folder.canWrite, s"Folder $folder is not read/writable")
    } else {
      require(folder.mkdirs(), s"Folder $folder could not be created")
    }
  }

  @inline def file(name: String) = new File(folder, name)

  private def readEntry(f: File): Option[(Entry[A], B)] = blocking {
    val in = DataInput.open(f)
    try {
      if (in.size >= 4 && (in.readInt() == COOKIE)) {
        val key   = keySerializer  .read(in)
        val value = valueSerializer.read(in)
        try {
          if (accept(value)) {
            val uid = sync.synchronized(nextUnique())
            val e = new Entry(key, lastModified = f.lastModified(), entrySize = f.length(),
              extraSize = space(value), unique = uid)
            Some(e -> value)
          } else {
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

  private def writeEntry(f: File, key: A, value: B) { blocking {
    val out = DataOutput.open(f)
    try {
      out.writeInt(COOKIE)
      keySerializer  .write(key,   out)
      valueSerializer.write(value, out)
    } catch {
      case NonFatal(_) =>
        out.close()
        f.delete()
    } finally {
      out.close()
    }
  }}

  // scan the cache directory and build information about size
  private def scan(): Seq[E] = blocking {
    val a = folder.listFiles(naming)
    if (a == null) {
      Nil // Limit(count = 0, space = 0L, age = Duration.Zero)
    } else {
      for (f <- a; (e, _) <- readEntry(f)) yield e
    }
  }
}