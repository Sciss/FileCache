# FileCache

## statement

FileCache is a simple building block for the Scala programming language, managing a directory with cache files. It is (C)opyright 2013&ndash;2015 by Hanns Holger Rutz. All rights reserved. This project is released under the [GNU Lesser General Public License](https://raw.github.com/Sciss/FileCache/master/LICENSE) v2.1+ and comes with absolutely no warranties. To contact the author, send an email to `contact at sciss.de`

## linking

To link to this library, use either of the following artifacts:

    "de.sciss" %% "filecache-mutable" % v
    "de.sciss" %% "filecache-txn"     % v

The current version `v` is `"0.3.2"`. The `-mutable` variant provides a (thread-safe) mutable cache object, whereas the `-txn` variant uses the [scala-stm](https://github.com/nbronson/scala-stm) software transactional memory.

## building

This project currently builds against Scala 2.11, 2.10, using sbt 0.13.

The project is documented through scaladoc run `sbt doc` to create the API docs.

## documentation

Here is an example of the mutable package, the transactional package works analogously. A cache is represented by the `MutableProducer[Key, Value]` type. Therefore, a resource (the value) is identified by and can be looked up with a key. A producer is created by calling `apply` on the companion object, requiring implicit values for key and value serializers (`de.sciss.serial.ImmutableSerializer`). Serializers are readily available for primitive types and some combinators (e.g. `Tuple2` or `IndexedSeq`).

For example, let us assume the key type is `String`, and the value type is `IndexedSeq[Int]`. First of all, we need to create a configuration for the cache. A mutable `ConfigBuilder` is obtained by calling `Config[Key, Value]()`. It will be implicitly frozen as a `Config` object when the builder is passed into the producer constructor. We'll go with the defaults except changing the cache capacity to hold at maximum two entries (the default is unlimited), and we'll assign an eviction function that prints to the console when a cache entry is removed from disk.

```scala

    import de.sciss.filecache._

    type Vec[+A] = collection.immutable.IndexedSeq[A]
    val c = Config[String, Vec[Int]]()
    c.capacity = Limit(count = 2)
    c.evict = { (key, value) => println(s"Evicted key $key") }
    val p = MutableProducer(c)
```

The producer is like an exclusive handle to resources. A resource can be obtained with method

```scala

    def acquire(key: A)(source: => B): Future[B]
````

Where the `source` thunk is executed only if the resource for `key` was not found in the cache. When the resource is not used any longer, it must be released using

```scala

    def release(key: A): Unit
```

If your application wants to be able to have multiple handles on a resource, a second structure `MutableConsumer` can be used which simply wraps a producer with an internal use count. Here the acquirement is a more simple method `def acquire(key: A): Future[B]` which may be called multiple times. Each acquirement should be matched with an eventual `release` call.

For our example, we will just use the exclusive producer. Cache production happens inside a `Future`, so if we want to see the result, we can use the future's `foreach` method. A suitable `ExecutionContext` can be found by importing `p.executionContext` (that context was defined in the original configuration).

```scala

    import p.executionContext
    import scala.concurrent._
    import duration.Duration

    val foo1 = p.acquire("foo") { println("Producing..."); Vector(1, 2, 3) }
    Await.result(foo1, Duration.Inf)  // Vector(1, 2, 3)
```

Let's release and re-acquire that resource. It should be cached:

```scala

    p.release("foo")
    val foo2 = p.acquire("foo")(???)
    Await.result(foo2, Duration.Inf)  // Vector(1, 2, 3)
```

If we release `"foo"` and produce new entries in the cache, we can witness the eviction:

```scala

    p.release("foo")
    val bar = p.acquire("bar") { Vector(4, 5, 6) }
    val baz = p.acquire("baz") { Vector(7, 8, 9) }  // causes eviction of "foo"
```

Note that an entry is never evicted while acquired.

Some more notes:

- the capacity `Limit` has two conditions, a `count` for the maximum number of entries and a `space` for the maximum total size in bytes. A value of `-1` for each of these (default) means no limit. Both limits work together, so if both `count` and `space` are specified, the capacity will be constrained to satisfy these two properties.
- if using a `space` limit, the `space` _function_ in the configuration must be provided. This function is given a key-value pair and must return the resource size in bytes. The reason this is not automatically equated with the size of the serialized value is that often a resource extends beyond the directly given value. For example, if the cache is used to produce waveform overviews for a sound file, the value might just point to the waveform file. But the size of the resource is then determined by the size of that waveform file, not the cache entry which is just the path name to that file.
- a producer can be disposed calling the `dispose` method. This will release all currently acquired keys.
- the cache directory is specified in the configuration using the `folder` variable. A second variable `extension` can be used to specify the file extension.
- the current cache size is available through the `usage` method on a producer
- cache values may become invalid for some reason. As an example, imagine again the sound file waveform cache. If the sound file whose waveform is produced has changed, then obviously the waveform overview must be re-computed. If that case may occur in your scenario, the function `accept` in the configuration should be specified. It is given a key-value pair and must return a boolean. A return value `true` indicates that the cache entry is still valid, a value of `false` will cause the produce to re-run the producer function and overwrite the cache entry with the new result.
