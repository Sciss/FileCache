package de.sciss.filecache

import org.scalatest.fixture
import org.scalatest.matchers.ShouldMatchers
import java.io.File
import scala.concurrent.{Await, Future}
import scala.util.{Success, Try}
import scala.concurrent.duration._

class FileCacheSpec extends fixture.FlatSpec with ShouldMatchers {
  final type FixtureParam = File

  final def withFixture(test: OneArgTest) {
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

  "FileCache" should ("have as advertised") in { f =>
    val cfg     = FileCache.Config[Int, Int]()
    cfg.folder  = f
    val cache   = FileCache(cfg)
    assert(cache.usage === Limit(0, 0))
    assert(cache.acquire(100, 2000).unwind === Success(2000))
    assert(cache.usage === Limit(1, 12))
    assert(cache.acquire(101, 3000).unwind === Success(3000))
    assert(cache.usage === Limit(2, 24))
    cache.release(100)
    assert(cache.usage === Limit(2, 24))
    assert(cache.acquire(100, 2001).unwind === Success(2000)) // finds acceptable existing value
    assert(cache.usage === Limit(2, 24))
  }
}