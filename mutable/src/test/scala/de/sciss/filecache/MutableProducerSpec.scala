package de.sciss.filecache

import java.io.File

import org.scalatest.Outcome
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

// TODO
// - hash collisions are _not_ yet tested!
// - rejections      are _not_ yet tested!
/*
  to run only this test:

  testOnly de.sciss.filecache.MutableProducerSpec
 */
class MutableProducerSpec extends FixtureAnyFlatSpec with Matchers {
  final type FixtureParam = File
  
  val isJDK8: Boolean = sys.props("java.version").startsWith("1.8.")

  final def withFixture(test: OneArgTest): Outcome = {
    val f = File.createTempFile(".cache", "")
    f.delete()
    f.mkdir()
    try {
      test(f)
    }
    finally {
      if (!f.delete()) f.deleteOnExit()
    }
  }

  private implicit class Unwind[A](fut: Future[A]) {
    def unwind: Try[A] = {
      Await.ready(fut, 4.seconds)
      fut.value.get
    }
  }

  // XXX TODO: last-modified does not work reliably here -- https://git.iem.at/sciss/FileCache/-/issues/6
  // need a better way to test this
  ignore /*"Mutable Producer"*/ should "behave as advertised" in { f =>
    val cfg     = Config[Int, Int]()
    cfg.folder  = f
    val cache   = MutableProducer(cfg)
    assert(cache.usage === Limit(0, 0))
    assert(cache.acquire(100)(2000).unwind === Success(2000))
    assert(cache.usage === Limit(0, 0)) // Limit(1, 12)
    Thread.sleep(500)  // ensure different modification dates
    assert(cache.acquire(101)(3000).unwind === Success(3000))
    assert(cache.usage === Limit(0, 0)) // Limit(2, 24)
    cache.release(100)
    assert(cache.usage === Limit(0, 0)) // Limit(2, 24)
    assert(cache.acquire(100)(2001).unwind === Success(2000)) // finds acceptable existing value
    assert(cache.usage === Limit(0, 0)) // Limit(2, 24)

    an [IllegalStateException] should be thrownBy { cache.acquire(100)(666) }
    assert(cache.usage === Limit(0, 0)) // Limit(2, 24)

    an [IllegalStateException] should be thrownBy { cache.release(666) }
    assert(cache.usage === Limit(0, 0)) // Limit(2, 24)

    cache.dispose()

    //    println("\n\nIn folder:\n")
    //    f.listFiles().foreach(println)
    //    println("\n\n")

    cfg.capacity  = Limit(count = 3)
    val cache1    = MutableProducer(cfg)
    cache1.activity.unwind
    assert(cache1.usage === Limit(2, 24))
    assert(cache1.acquire(100)(2002).unwind === Success(2000))
    cache1.dispose()

    var evicted   = Vector.empty[Int]
    cfg.space     = (_, i) => i.toLong   // why not, this is just a test...
    cfg.evict     = (_, i) => evicted :+= i
    cfg.capacity  = Limit(count = 3)
    val cache2    = MutableProducer(cfg)
    cache2.activity.unwind
    assert(cache2.usage === Limit(2, 24 + 5000))
    val res = cache2.acquire(300)(4000).unwind
    res match {
      case Failure(e) => e.printStackTrace()
      case _ =>
    }
    assert(res === Success(4000))
    assert(cache2.usage === Limit(3, 36 + 9000))

    cache2.release(300)
    assert(cache2.acquire(300)(5000).unwind === Success(4000))
    assert(evicted.isEmpty)
    assert(cache2.usage === Limit(3, 36 + 9000))

    cache2.release(300)
    assert(cache2.acquire(400)(6000).unwind === Success(6000))
    cache2.activity.unwind

    // https://git.iem.at/sciss/FileCache/-/issues/6    
    if (isJDK8) {
      assert(evicted === Vector(2000))  // key 100 / value 2000 is the oldest entry
      assert(cache2.usage === Limit(3, 36 + 9000 + 6000 - 2000))
    }

    evicted = Vector.empty
    assert(cache2.acquire(100)(7000).unwind === Success(7000))
    cache2.activity.unwind

    // https://git.iem.at/sciss/FileCache/-/issues/6    
    if (isJDK8) {
      assert(evicted === Vector(3000))  // key 101 / value 3000 is the oldest entry
      assert(cache2.usage === Limit(3, 36 + 9000 + 6000 - 2000 + 7000 - 3000))
    }
  }
}