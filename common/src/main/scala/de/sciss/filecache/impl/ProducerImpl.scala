/*
 *  ProducerImpl.scala
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

import scala.annotation.elidable
import scala.concurrent._
import de.sciss.file._
import de.sciss.filecache.impl.ProducerImpl._
import scala.Some
import de.sciss.serial.{ImmutableSerializer, DataInput, DataOutput}
import scala.util.control.NonFatal
import java.io.FilenameFilter

private[filecache] object ProducerImpl {
  final val COOKIE  = 0x2769F746

  @elidable(elidable.CONFIG) def debug(what: => String): Unit = println(s"<cache> $what")

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
private[filecache] trait ProducerImpl[A, B] extends FilenameFilter {
  protected type E = ProducerImpl.Entry[A]

  // ---- abstract ----

  val config: Config[A, B]

  protected def addUsage(space: Long, count: Int): Unit

  protected def keySerializer  : ImmutableSerializer[A]
  protected def valueSerializer: ImmutableSerializer[B]

  // -------------------- FilenameFilter --------------------

  def accept(dir: File, name: String): Boolean =
    name.endsWith(extension) && name.substring(0, name.length - extension.length).forall(c =>
      (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
    )

  // ---- implemented ----
  import config.{folder, capacity, space, evict, accept => acceptValue}

  private def extension = "." + config.extension

  implicit final def executionContext: ExecutionContext = config.executionContext

  final protected val hasLimit = capacity.count > 0 || capacity.space > 0L

  final protected def validateFolder(): Unit =
    if (folder.exists()) {
      require(folder.isDirectory && folder.canRead && folder.canWrite, s"Folder $folder is not read/writable")
    } else {
      require(folder.mkdirs(), s"Folder $folder could not be created")
    }

  /** Tries to read an entry and associated value from a given file. If that file does not exist,
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
  final protected def readEntry(hash: Int, update: Option[A]): Option[(Entry[A], B)] = blocking {
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
          addUsage(space = -(n + r), count = -1)
        }
        None
      }
    }
  }

  /** Writes the key-value entry to the given file, and returns an `Entry` for it.
    * The file date is not touched but should obviously correspond to the current
    * system time. It returns the entry thus generated
    */
  final protected def writeEntry(hash: Int, key: A, value: B): E = { blocking {
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

  final protected def readKeyValue(f: File): Option[(A, B)] = blocking {
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

  @inline final protected def hashToName(hash: Int): String = s"${hash.toHexString}$extension"
  @inline final protected def nameToHash(name: String): Int =
    java.lang.Long.parseLong(name.substring(0, name.length - extension.length), 16).toInt // Integer.parseInt fails for > 0x7FFFFFFF !!
  //    Integer.parseInt(name.substring(0, name.length - extension.length), 16)

  override def toString = s"Producer@${hashCode().toHexString}"
}