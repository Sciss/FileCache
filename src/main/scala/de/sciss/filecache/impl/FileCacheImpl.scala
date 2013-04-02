package de.sciss.filecache
package impl

import java.io.{FilenameFilter, File}
import collection.mutable

object FileCacheImpl {
  private final val CACHE_EXT = ".cache"
  private final val HEX_CHARS = "0123456789ABCDEF"

  private final case class CacheEntry(file: File, lastModified: Long, size: Long)

  private object FilenameFilter extends FilenameFilter {
    def accept(dir: File, name: String): Boolean = name.endsWith(CACHE_EXT)
  }
}

class FileCacheImpl /* extends FileCache */ {
  import FileCacheImpl._

  private var _folder: File = null
  private var _capacity     = 0
  private var folderSize    = 0L
  private val cacheSet      = mutable.Set.empty[CacheEntry]

  def add(f: File) {
    val ce = CacheEntry(f, f.lastModified(), f.length())
    if (cacheSet.add(ce)) {
      folderSize += ce.size
      trimToCapacity()
    }
  }

  def remove(f: File) {
    val ce = CacheEntry(f, f.lastModified(), f.length())
    if (cacheSet.remove(ce)) {
      folderSize -= ce.size
    }
    if (f.exists && !f.delete) {
      val refName: String = f.getName
      val suffixIdx: Int = refName.lastIndexOf('.')
      val name: String = if (suffixIdx == -1) refName else refName.substring(0, suffixIdx)
      val tempFile: File = new File(_folder, name + ".tmp")
      tempFile.delete
      f.renameTo(tempFile)
      tempFile.deleteOnExit()
    }
  }

  def createCacheFileName(reference: File): File = {
    val strBuf  = new StringBuilder(16)
    var hash    = reference.hashCode()
    var i = 0
    while (i < 8) {
      strBuf.append(HEX_CHARS.charAt(hash & 0x0F))
      i += 1
      hash >>= 4
    }
    strBuf.append(CACHE_EXT)
    new File(_folder, strBuf.toString())
  }

  def folder = _folder
  def folder_=(value: File) {
    if (_folder != value) {
      setFolderAndCapacity(value, _capacity)
    }
  }

  def capacity = _capacity
  def capacity_=(value: Int) {
    if (_capacity != value) {
      setFolderAndCapacity(_folder, value)
    }
  }

  def setFolderAndCapacity(folder: String, capacity: Int) {
    setFolderAndCapacity(new File(folder), capacity)
  }

  def setFolderAndCapacity(folder: File, capacity: Int) {
    if ((folder == null) || !(folder == _folder)) {
      if (_folder != null) {
        clearCache()
      }
      _folder = folder
      makeSureFolderExists()
    }
    if (_capacity != capacity) {
      _capacity = capacity
    }
    updateFileList()
    trimToCapacity()
  }

  private def makeSureFolderExists() {
    if (_folder != null) _folder.mkdirs()
  }

  private def updateFileList() {
    val files: Array[File] = if (_folder == null) null else _folder.listFiles(FilenameFilter)
    cacheSet.clear()
    folderSize = 0
    if (files == null) return
    var ce: CacheManager.CacheEntry = null
    var i: Int = 0
    while (i < files.length) {
      ce = new CacheManager.CacheEntry(files(i))
      cacheSet.add(ce)
      folderSize += ce.size
      i += 1
    }
  }

  private def trimToCapacity() {
    var ce: CacheManager.CacheEntry = null
    val capaBytes: Long = _capacity.asInstanceOf[Long] * 0x100000
    while (folderSize > capaBytes) {
      ce = cacheSet.first.asInstanceOf[CacheManager.CacheEntry]
      cacheSet.remove(ce)
      folderSize -= ce.size
      if (!ce.file.delete) {
        ce.file.deleteOnExit
      }
    }
  }

  def clear() {
    var ce: CacheManager.CacheEntry = null
    while (!cacheSet.isEmpty) {
      ce = cacheSet.last.asInstanceOf[CacheManager.CacheEntry]
      cacheSet.remove(ce)
      if (!ce.file.delete) {
        ce.file.deleteOnExit
      }
    }
    folderSize = 0
  }
}