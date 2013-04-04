package de.sciss.filecache

import concurrent.{ExecutionContext, Future}
import java.io.{FilenameFilter, File}
import impl.{NewImpl => Impl}
import language.implicitConversions
import de.sciss.serial.ImmutableSerializer

object FileCache {
  // Note: type `A` is not used in this trait, but it make instantiating the actual cache manager easier,
  // because we have to specify types only with `FileCache.Config[A, B]`, and can then successively call
  // `FileCache(cfg)`.
  trait ConfigLike[A, B] {
    /** The directory where the cached values are stored. If this directory does not exist
      * upon cache creation, it will be created on the fly.
      */
    def folder: File

    /** Given a key hash, compute the filename of the cache entry. The default function uses a
      * hexadecimal representation of the hash along with an extension of `.cache`
      */
    def naming: NameProvider

    /** The maximum capacity of the cache. */
    def capacity: Limit

    /** Acceptor function.
      * Given an initially found value, this function should return `true` if the value is still valid,
      * or `false` if it is invalid and should be recomputed. The default function always returns `true`,
      * i.e. assumes that values never become invalid.
      */
    def accept: B => Boolean

    /** Associate resources space function.
      * Given a value, this function should compute the size in bytes of any additional resources used
      * by this entry. The default funtion always returns zero, i.e. assumes that there are no additional
      * resources associated with a value.
      */
    def space: B => Long

    /** A function which is being called when an entry is evicted from cache. The function must ensure
      * that any associated resources are disposed of.
      */
    def evict: B => Unit

    def executionContext: ExecutionContext
  }
  object Config {
    def apply[A, B]() = new ConfigBuilder[A, B]
    implicit def build[A, B](b: ConfigBuilder[A, B]): Config[A, B] = b.build
  }
  final case class Config[A, B] private[FileCache](folder: File, naming: NameProvider, capacity: Limit,
                                                   accept: B => Boolean, space: B => Long, evict: B => Unit,
                                                   executionContext: ExecutionContext)
    extends ConfigLike[A, B]

  final class ConfigBuilder[A, B] private[FileCache]() extends ConfigLike[A, B] {
    var folder    = new File(sys.props("java.io.tmpdir"))
    var naming    = NameProvider.default
    var capacity  = Limit()
    var accept    = (_: B) => true
    var space     = (_: B) => 0L
    var evict     = (_: B) => ()

    var executionContext  = ExecutionContext.global

    override def toString = s"FileCache.ConfigBuilder@${hashCode().toHexString}"

    def build: Config[A, B] = Config(folder = folder, naming = naming, capacity = capacity, accept = accept,
                                     space = space, evict = evict, executionContext = executionContext)
  }

  def apply[A, B](config: Config[A, B])(implicit keySerializer  : ImmutableSerializer[A],
                                                 valueSerializer: ImmutableSerializer[B]): FileCache[A, B] =
    new Impl(config)

  object NameProvider {
    /** Creates a name provider which converts the key hash into a sequence of hexadecimal digits,
      * and appends a given extension. The extension must consist only of letters or digits, and
      * must not include the leading period.
      *
      * @param extension  the extension to use, excluding leading period
      */
    def hex(extension: String): NameProvider = {
      require(extension.forall(_.isLetterOrDigit))
      new Hex("." + extension)
    }
    val default: NameProvider = new Hex(".cache")

    // note: extension includes the period here!
    private final class Hex(extension: String) extends NameProvider {
      def accept(dir: File, name: String): Boolean =
        name.endsWith(extension) && name.substring(name.length - extension.length).forall(c =>
          (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
        )

      /** Given a key hash, produces a filename for the cache entry. */
      def apply(hash: Int): String = s"${hash.toHexString}$extension"
    }
  }
  /** A trait that provides filenames for key caches and detects whether a file is a valid cache filename. */
  trait NameProvider extends FilenameFilter {
    /** Given a key hash, produces a filename for the cache entry. */
    def apply(hash: Int): String
  }
}
trait FileCache[A, B] {
  def acquire    (key: A, producer: => B)        : Future[B]
  def acquireWith(key: A, producer: => Future[B]): Future[B]
  def release    (key: A): Unit

  implicit def executionContext: ExecutionContext

// these could come in at a later point; for now let's stick to the minimal interface.

//  def clear(): Unit
//  def sweep(): Unit

//  var capacity: Limit
//  def trim(limit: Limit): Unit
//  def usage: Limit
}