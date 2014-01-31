/*
 *  Config.scala
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

import de.sciss.file._
import scala.concurrent.ExecutionContext
import language.implicitConversions

// Note: type `A` is not used in this trait, but it makes instantiating the actual cache manager easier,
// because we have to specify types only with `Cache.Config[A, B]`, and can then successively call
// `Cache(cfg)`.
sealed trait ConfigLike[-A, -B] {
  /** The directory where the cached values are stored. If this directory does not exist
    * upon cache creation, it will be created on the fly.
    */
  def folder: File

  /** The file name extension to use, excluding leading period.
    * It must only consist of letters and digits.
    */
  def extension: String

  /** The maximum capacity of the cache. */
  def capacity: Limit

  /** Acceptor function.
    * Given an initially found value, this function should return `true` if the value is still valid,
    * or `false` if it is invalid and should be recomputed. This function is used only once during the initial
    * directory scan of the producer after it is created.
    */
  def accept: (A, B) => Boolean

  /** Associate resources space function.
    * Given a value, this function should compute the size in bytes of any additional resources used
    * by this entry.
    */
  def space: (A, B) => Long

  /** A function which is being called when an entry is evicted from cache. The function must ensure
    * that any associated resources are disposed of.
    */
  def evict: (A, B) => Unit

  /** The context used by the cache to spawn future computations. */
  def executionContext: ExecutionContext
}
object Config {
  /** Creates a new configuration builder with default values. */
  def apply[A, B]() = new ConfigBuilder[A, B]
  /** Implicitly converts a builder to an immutable configuration value. */
  implicit def build[A, B](b: ConfigBuilder[A, B]): Config[A, B] = b.build
}

/** The configuration for the producer, containing information about the cache folder, cache capacity, etc. */
final case class Config[-A, B] private[filecache](folder: File, extension: String, capacity: Limit,
                                                 accept: (A, B) => Boolean, space: (A, B) => Long,
                                                 evict: (A, B) => Unit,
                                                 executionContext: ExecutionContext)
  extends ConfigLike[A, B]

/** A configuration builder is a mutable version of `Config` and will be implicitly converted to the latter
  * when passed into `Producer.apply`.
  */
final class ConfigBuilder[A, B] private[filecache]() extends ConfigLike[A, B] {
  private var _folder     = Option.empty[File]
  private var _extension  = "cache"

  /** @inheritdoc
    *
    * By default this will lazily create a temporary directory deleted on application exit.
    * If this value is set via `folder_=`, that setting replaces the default behavior.
    */
  def folder: File = _folder.getOrElse {
    val f = File.createTemp(".cache", "")
    f.delete()
    f.mkdir()
    f.deleteOnExit()
    _folder = Some(f)
    f
  }
  def folder_=(value: File): Unit = _folder = Some(value)

  /** @inheritdoc
    *
    * The default value is `Limit(-1, -1)` (unlimited capacity).
    */
  var capacity  = Limit()
  /** @inheritdoc
    *
    * The default function always returns `true`, i.e. assumes that values never become invalid.
    */
  var accept    = (_: A, _: B) => true

  /** @inheritdoc
    *
    * The default function always returns zero, i.e. assumes that there are no additional
    * resources associated with a value.
    */
  var space     = (_: A, _: B) => 0L

  /** @inheritdoc
    *
    * The default function is a no-op.
    */
  var evict     = (_: A, _: B) => ()

  /** @inheritdoc
    *
    * The default value is `"cache"`.
    */
  def extension = _extension
  def extension_=(value: String): Unit = {
    require(value.forall(_.isLetterOrDigit))
    _extension = value
  }

  /** @inheritdoc
    *
    * The default value is `ExecutionContext.global`.
    */
  var executionContext: ExecutionContext = ExecutionContext.global

  override def toString = s"Cache.ConfigBuilder@${hashCode().toHexString}"

  def build: Config[A, B] = Config(folder = folder, extension = extension, capacity = capacity, accept = accept,
    space = space, evict = evict, executionContext = executionContext)
}

