package de.sciss.filecache

object FileCache {
}
trait FileCache[A, B] {
  def get(key: A): Option[B]

}