package com.twitter.finagle.service

import org.specs.Specification
import org.specs.mock.Mockito

import com.twitter.util.{Promise, Future, Return, Throw, Try, Time}
import com.twitter.conversions.time._

import com.twitter.finagle.{Service, WriteException, MockTimer}
import com.twitter.finagle.stats.{StatsReceiver, Stat}

object RetryingFilterSpec extends Specification with Mockito {
  "RetryingFilter" should {
    val backoffs = Stream(1.second, 2.seconds, 3.seconds)
    val stats = mock[StatsReceiver]
    val stat = mock[Stat]
    val timer = new MockTimer
    stats.stat("retries") returns stat
    val shouldRetry = mock[PartialFunction[Try[Int], Boolean]]
    shouldRetry.isDefinedAt(any) returns true
    shouldRetry(any[Try[Int]]) answers {
      case Throw(_:WriteException) =>
        true
      case _ => false
    }
    val filter = new RetryingFilter[Int, Int](backoffs, stats, shouldRetry, timer)
    val service = mock[Service[Int, Int]]
    service(123) returns Future(321)
    val retryingService = filter andThen service

    "always try once" in {
      retryingService(123)() must be_==(321)
      there was one(service)(123)
      there was no(stat).add(any[Int])
    }

    "when failed with a WriteException, consult the retry strategy" in Time.withCurrentTimeFrozen { tc =>
      service(123) returns Future.exception(new WriteException(new Exception))
      val f = retryingService(123)
      there was one(service)(123)
      f.isDefined must beFalse
      timer.tasks must haveSize(1)

      service(123) returns Future(321)  // we succeed next time; tick!
      tc.advance(1.second); timer.tick()

      there were two(service)(123)
      there was one(stat).add(1)
      f() must be_==(321)
    }

    "give up when the retry strategy is exhausted" in Time.withCurrentTimeFrozen { tc =>
      service(123) returns Future.exception(new WriteException(new Exception("i'm exhausted")))
      val f = retryingService(123)
      1 to 3 foreach { i =>
        f.isDefined must beFalse
        there were i.times(service)(123)
        there was no(stat).add(any[Int])
        tc.advance(i.seconds); timer.tick()
      }

      f.isDefined must beTrue
      f.isThrow must beTrue
      f() must throwA(new WriteException(new Exception("i'm exhausted")))
    }

    "when failed with a non-WriteException, fail immediately" in {
      service(123) returns Future.exception(new Exception("WTF!"))
      retryingService(123)() must throwA(new Exception("WTF!"))
      there was one(service)(123)
      timer.tasks must beEmpty
      there was no(stat).add(any[Int])
    }

    "when no retry occurs, no stat update" in {
      retryingService(123)() must be_==(321)
      there was no(stat).add(any[Int])
    }
  }

  "Backoff" should {
    "Backoff.exponential" in {
      val backoffs = Backoff.exponential(1.seconds, 2) take 10
      backoffs.force.toSeq must be_==(0 until 10 map { i => (1 << i).seconds })
    }

    "Backoff.linear" in {
      val backoffs = Backoff.linear(2.seconds, 10.seconds) take 10
      backoffs.head must be_==(2.seconds)
      backoffs.tail.force.toSeq must be_==(1 until 10 map { i => 2.seconds + 10.seconds * i })
    }

    "Backoff.const" in {
      val backoffs = Backoff.const(10.seconds) take 10
      backoffs.force.toSeq must be_==(0 until 10 map { _ => 10.seconds})
    }
  }
}
