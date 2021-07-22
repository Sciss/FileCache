package de.sciss.filecache

import java.io.File

import org.scalatest.Outcome
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.stm.{Ref, atomic}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

// TODO
// - hash collisions are _not_ yet tested!
// - rejections      are _not_ yet tested!
/*
  to run only this test:

  testOnly de.sciss.filecache.TxnProducerSpec
 */
class TxnProducerSpec extends FixtureAnyFlatSpec with Matchers {
  final type FixtureParam = File

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

  "Transactional Producer" should "have as advertised" in { f =>
    val cfg     = Config[Int, Int]()
    cfg.folder  = f
    val (cache, state0) = atomic { implicit tx =>
      val _cache = TxnProducer(cfg)
      assert(_cache.usage === Limit(0, 0))
      _cache -> _cache.acquire(100)(2000)
    }
    assert(state0.unwind === Success(2000))
    atomic { implicit tx =>
      assert(cache.usage === Limit(0, 0)) // Limit(1, 12)
    }
    Thread.sleep(10)  // ensure different modification dates
    val state1 = atomic { implicit tx =>
      cache.acquire(101)(3000)
    }
    assert(state1.unwind === Success(3000))
    val state2 = atomic { implicit tx =>
      assert(cache.usage === Limit(0, 0)) // Limit(2, 24)
      cache.release(100)
      assert(cache.usage === Limit(0, 0)) // Limit(2, 24)
      cache.acquire(100)(2001)
    }
    assert(state2.unwind === Success(2000)) // finds acceptable existing value
    atomic { implicit tx =>
      assert(cache.usage === Limit(0, 0)) // Limit(2, 24)
    }
    an [IllegalStateException] should be thrownBy {
      atomic { implicit tx =>
        cache.acquire(100)(666)
      }
    }

    atomic { implicit tx =>
      assert(cache.usage === Limit(0, 0)) // Limit(2, 24)
    }

    an [IllegalStateException] should be thrownBy {
      atomic { implicit tx =>
        cache.release(666)
      }
    }

    atomic { implicit tx =>
      assert(cache.usage === Limit(0, 0)) // Limit(2, 24)

      cache.dispose()
    }

    cfg.capacity  = Limit(count = 3)
    val (cache1, state3) = atomic { implicit tx =>
      val _cache = TxnProducer(cfg)
      _cache -> _cache.activity
    }
    state3.unwind

    val state4 = atomic { implicit tx =>
      assert(cache1.usage === Limit(2, 24))
      cache1.acquire(100)(2002)
    }

    assert(state4.unwind === Success(2000))

    atomic { implicit tx =>
      cache1.dispose()
    }

    val evicted   = Ref(Vector.empty[Int])
    cfg.space     = (_, i) => i.toLong   // why not, this is just a test...
    cfg.evict     = (_, i) => evicted.single.transform(_ :+ i)
    cfg.capacity  = Limit(count = 3)
    val (cache2, state5) = atomic { implicit tx =>
      val _cache = TxnProducer(cfg)
      _cache -> _cache.activity
    }
    state5.unwind

    val state6 = atomic { implicit tx =>
      assert(cache2.usage === Limit(2, 24 + 5000))
      cache2.acquire(300)(4000)
    }
    val res = state6.unwind
    res match {
      case Failure(e) => e.printStackTrace()
      case _ =>
    }
    assert(res === Success(4000))

    val state7 = atomic { implicit tx =>
      assert(cache2.usage === Limit(3, 36 + 9000))
      cache2.release(300)
      cache2.acquire(300)(5000)
    }

    assert(state7.unwind === Success(4000))
    assert(evicted.single().isEmpty)

    val state8 = atomic { implicit tx =>
      assert(cache2.usage === Limit(3, 36 + 9000))
      cache2.release(300)
      cache2.acquire(400)(6000)
    }

    assert(state8.unwind === Success(6000))

    val state9 = atomic { implicit tx =>
      cache2.activity
    }
    state9.unwind

    // XXX TODO: ignore for now -- https://github.com/Sciss/FileCache/issues/7
    // assert(evicted.single() === Vector(2000))  // key 100 / value 2000 is the oldest entry

    // XXX TODO: ignore for now -- https://github.com/Sciss/FileCache/issues/7
//    atomic { implicit tx =>
//      assert(cache2.usage === Limit(3, 36 + 9000 + 6000 - 2000))
//    }

    evicted.single.set(Vector.empty)

    val state10 = atomic { implicit tx =>
      cache2.acquire(100)(7000)
    }
    // XXX TODO --- why does this fail on JDK11?
//    assert(state10.unwind === Success(7000))

    val state11 = atomic { implicit tx =>
      cache2.activity
    }
    state11.unwind

    // XXX TODO: ignore for now -- https://github.com/Sciss/FileCache/issues/7
//    assert(evicted.single() === Vector(3000))  // key 101 / value 3000 is the oldest entry

    // XXX TODO: ignore for now -- https://github.com/Sciss/FileCache/issues/7
//    atomic { implicit tx =>
//      assert(cache2.usage === Limit(3, 36 + 9000 + 6000 - 2000 + 7000 - 3000))
//    }
  }
}