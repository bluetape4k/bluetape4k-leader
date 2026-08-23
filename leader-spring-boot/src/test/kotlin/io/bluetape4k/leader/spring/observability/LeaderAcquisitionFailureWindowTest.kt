package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.metrics.SkipReason
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LeaderAcquisitionFailureWindowTest {

    private val now = Instant.parse("2026-07-15T00:00:00Z")
    private val options = LeaderElectionOptions()

    @Test
    fun `backend error is counted while contention and fail open are ignored`() {
        val window = LeaderAcquisitionFailureWindow(
            window = Duration.ofMinutes(5),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            capacity = 4,
        )

        window.onLockNotAcquired("job-a", options, SkipReason.CONTENTION)
        window.onLockNotAcquired("job-b", options, SkipReason.FAIL_OPEN_FORCED)
        window.onLockNotAcquired("job-c", options, SkipReason.BACKEND_ERROR)

        window.view().let { view ->
            view.count shouldBeEqualTo 1
            view.lastFailureAt shouldBeEqualTo now
            view.overflowed.shouldBeFalse()
        }
    }

    @Test
    fun `lower boundary is included and older timestamp is pruned`() {
        val clock = MutableClock(now)
        val window = LeaderAcquisitionFailureWindow(Duration.ofSeconds(10), clock, capacity = 4)

        clock.current = now.minusSeconds(10)
        window.onLockNotAcquired("boundary", options, SkipReason.BACKEND_ERROR)
        clock.current = now.minusSeconds(11)
        window.onLockNotAcquired("expired", options, SkipReason.BACKEND_ERROR)
        clock.current = now

        window.view().count shouldBeEqualTo 1
        window.view().lastFailureAt shouldBeEqualTo now.minusSeconds(10)
    }

    @Test
    fun `capacity eviction reports lower bound and clears overflow after expiry`() {
        val clock = MutableClock(now)
        val window = LeaderAcquisitionFailureWindow(Duration.ofMinutes(5), clock, capacity = 2)

        repeat(3) { index ->
            window.onLockNotAcquired("job-$index", options, SkipReason.BACKEND_ERROR)
        }

        window.view().let { view ->
            view.count shouldBeEqualTo 2
            view.capacity shouldBeEqualTo 2
            view.overflowed.shouldBeTrue()
        }

        clock.current = now.plus(Duration.ofMinutes(6))
        window.view().let { view ->
            view.count shouldBeEqualTo 0
            view.lastFailureAt.shouldBeNull()
            view.overflowed.shouldBeFalse()
        }
    }

    @Test
    fun `invalid window and capacity fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderAcquisitionFailureWindow(Duration.ZERO, Clock.fixed(now, ZoneOffset.UTC), 4)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAcquisitionFailureWindow(Duration.ofSeconds(1), Clock.fixed(now, ZoneOffset.UTC), 0)
        }
    }

    @Test
    fun `clock failure is swallowed by best effort recorder`() {
        val window = LeaderAcquisitionFailureWindow(Duration.ofSeconds(5), ThrowingClock(), capacity = 4)

        window.onLockNotAcquired("job", options, SkipReason.BACKEND_ERROR)

        window.view(now).count shouldBeEqualTo 0
    }

    @Test
    fun `concurrent records never exceed capacity`() {
        val window = LeaderAcquisitionFailureWindow(
            window = Duration.ofMinutes(5),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            capacity = 64,
        )
        val pool = Executors.newFixedThreadPool(8)
        val failures = AtomicReference<Throwable?>(null)

        try {
            val tasks = (0 until 800).map { index ->
                pool.submit {
                    runCatching {
                        window.onLockNotAcquired("job-$index", options, SkipReason.BACKEND_ERROR)
                    }.onFailure { failures.compareAndSet(null, it) }
                }
            }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
        }

        failures.get().shouldBeNull()
        (window.view().count <= 64).shouldBeTrue()
    }

    private class MutableClock(initial: Instant) : Clock() {
        private val zone = ZoneOffset.UTC
        var current: Instant = initial

        override fun instant(): Instant = current

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = this
    }

    private class ThrowingClock : Clock() {
        override fun instant(): Instant = throw IllegalStateException("clock unavailable")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }
}
