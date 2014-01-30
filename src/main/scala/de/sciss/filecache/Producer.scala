/*
 *  Producer.scala
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

import concurrent.{ExecutionContext, Future}
import java.io.File
import impl.{ProducerImpl => Impl}
import language.implicitConversions
import de.sciss.serial.ImmutableSerializer

object Producer {
  // Note: type `A` is not used in this trait, but it makes instantiating the actual cache manager easier,
  // because we have to specify types only with `Cache.Config[A, B]`, and can then successively call
  // `Cache(cfg)`.
  trait ConfigLike[-A, -B] {
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
      * or `false` if it is invalid and should be recomputed.
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
  final case class Config[-A, B] private[Producer](folder: File, extension: String, capacity: Limit,
                                                   accept: (A, B) => Boolean, space: (A, B) => Long,
                                                   evict: (A, B) => Unit,
                                                   executionContext: ExecutionContext)
    extends ConfigLike[A, B]

  /** A configuration builder is a mutable version of `Config` and will be implicitly converted to the latter
    * when passed into `Producer.apply`.
    */
  final class ConfigBuilder[A, B] private[Producer]() extends ConfigLike[A, B] {
    private var _folder     = Option.empty[File]
    private var _extension  = "cache"

    /** @inheritdoc
      *
      * By default this will lazily create a temporary directory deleted on application exit.
      * If this value is set via `folder_=`, that setting replaces the default behavior.
      */
    def folder: File = _folder.getOrElse {
      val f = File.createTempFile(".cache", "")
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

  /** Creates a new cache production instance.
    *
    * @param config           the cache configuration. Typically you pass in the configuration builder which is then
    *                         converted to an immutable `Config` instance.
    * @param keySerializer    the serializer used when writing keys to disk or reading keys from disk
    * @param valueSerializer  the serializer used when writing values to disk or reading values from disk
    * @tparam A               the key type
    * @tparam B               the value type
    */
  def apply[A, B](config: Config[A, B])(implicit keySerializer  : ImmutableSerializer[A],
                                                 valueSerializer: ImmutableSerializer[B]): Producer[A, B] =
    new Impl(config)
}

// note: because of the serialization, `B` cannot be made variant
trait Producer[-A, B] {
  /** Acquires the cache value of a given key.
    * A cache entry, like an exclusive lock, can only be acquired by one instance at a time, therefore if the
    * entry is still locked, this method throws an immediate `IllegalStateException`.
    *
    * If the entry is still found on disk, it will be re-used, given that the configuration's `accept` method
    * returns `true`. If the entry is not found or not accepted, a new value is produced by spawning the source
    * in its own future (using the configuration's `executionContext`).
    *
    * If the source naturally returns a future, use `acquireWith` instead.
    *
    * When the value is not used any more, the caller should invoke `release` to make it possible for the entry
    * to be evicted when over capacity. Only a released entry can be re-acquired.
    *
    * @param key      the key to look up
    * @param source   the source which is only used if the entry was not found or not accepted
    * @return         the future value of the cache entry (this might result in an I/O exception for example)
    */
  def acquire(key: A, source: => B): Future[B]

  /** Acquires the cache value of a given key.
    * This method is equivalent to `acquire` but takes a source in the form of a future. See `acquire` for
    * more details on the mechanism and requirements of this process.
    *
    * @param key      the key to look up
    * @param source   the source which is only used if the entry was not found or not accepted
    * @return         the future value of the cache entry (this might result in an I/O exception for example)
    */
  def acquireWith(key: A, source: => Future[B]): Future[B]

  /** Release a cache entry. The caller must have acquired the entry for the given key, using
    * `acquire` or `acquireWith`. If the entry is not locked, this method will throw an `IllegalStateException`.
    *
    * Releasing the entry makes it possible to evict it from the cache if the cache capacity is exhausted.
    *
    * @param key  the key to release
    */
  def release(key: A): Unit

  /** The context used by the cache to spawn future computations. This is directly taken from its
    * configuration, and is provided here for clients to easily import it as an implicit value, e.g.
    * to create its own futures.
    */
  implicit def executionContext: ExecutionContext

  // these could come in at a later point; for now let's stick to the minimal interface.

  //  def clear(): Unit
  //  def sweep(): Unit

  /** Reports the current statistics of the cache, which are number of entries, total size and age span. */
  def usage: Limit

  /** The configuration used to instantiate the producer. */
  def config: Producer.Config[A, B]

  /** Disposes this producer and makes it unavailable for future use.
    * Any attempt to call `acquireWith` or `release` after this invocation results in
    * an `IllegalStateException` being thrown.
    */
  def dispose(): Unit

  private[filecache] def activity: Future[Unit]

  //  var capacity: Limit
  //  def trim(limit: Limit): Unit
}