package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.map.IMap
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HazelcastMonotonicWaitTest {

    @Test
    fun `blocking wait budget은 주입한 monotonic ticker를 따른다`() {
        val lockMap = contendedMap()
        val lock = HazelcastLock(lockMap, "blocking-clock-jump")
        var tickerNanos = 1.seconds.inWholeNanoseconds
        val sleeps = mutableListOf<Long>()

        val acquired = lock.tryLock(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
            ticker = { tickerNanos },
            sleep = { millis ->
                sleeps += millis
                tickerNanos += millis.milliseconds.inWholeNanoseconds
            },
        )

        acquired.shouldBeFalse()
        sleeps shouldBeEqualTo listOf(50L, 50L)
        verify(exactly = 2) { lockMap.putIfAbsent(any(), any(), any<Long>(), TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `blocking zero negative wait은 한 번만 시도하고 sleep 하지 않는다`() {
        val lockMap = contendedMap()
        var sleepCount = 0

        listOf(Duration.ZERO, (-1).milliseconds).forEachIndexed { index, waitTime ->
            HazelcastLock(lockMap, "blocking-non-positive-$index").tryLock(
                waitTime = waitTime,
                leaseTime = 5.seconds,
                ticker = { 42L },
                sleep = { sleepCount++ },
            ).shouldBeFalse()
        }

        sleepCount shouldBeEqualTo 0
        verify(exactly = 2) { lockMap.putIfAbsent(any(), any(), any<Long>(), TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `blocking wait interruption은 호출자에게 전파한다`() {
        val lock = HazelcastLock(contendedMap(), "blocking-interruption")

        assertFailsWith<InterruptedException> {
            lock.tryLock(
                waitTime = 1.seconds,
                leaseTime = 5.seconds,
                ticker = { 42L },
                sleep = { throw InterruptedException("cancelled") },
            )
        }
    }

    @Test
    fun `suspend wait budget은 주입한 monotonic ticker를 따른다`() = runTest {
        val lockMap = contendedMap()
        val lock = HazelcastSuspendLock(lockMap, "suspend-clock-jump")
        var tickerNanos = 1.seconds.inWholeNanoseconds
        val delays = mutableListOf<Long>()

        val acquired = lock.tryLock(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
            ticker = { tickerNanos },
            delayMillis = { millis ->
                delays += millis
                tickerNanos += millis.milliseconds.inWholeNanoseconds
            },
        )

        acquired.shouldBeFalse()
        delays shouldBeEqualTo listOf(50L, 50L)
        verify(exactly = 2) { lockMap.putIfAbsent(any(), any(), any<Long>(), TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `suspend zero negative wait은 한 번만 시도하고 delay 하지 않는다`() = runTest {
        val lockMap = contendedMap()
        var delayCount = 0

        listOf(Duration.ZERO, (-1).milliseconds).forEachIndexed { index, waitTime ->
            HazelcastSuspendLock(lockMap, "suspend-non-positive-$index").tryLock(
                waitTime = waitTime,
                leaseTime = 5.seconds,
                ticker = { 42L },
                delayMillis = { delayCount++ },
            ).shouldBeFalse()
        }

        delayCount shouldBeEqualTo 0
        verify(exactly = 2) { lockMap.putIfAbsent(any(), any(), any<Long>(), TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `suspend wait cancellation은 호출자에게 전파한다`() = runTest {
        val lock = HazelcastSuspendLock(contendedMap(), "suspend-cancellation")
        val waiting = CompletableDeferred<Unit>()
        val contender = async {
            lock.tryLock(
                waitTime = 1.seconds,
                leaseTime = 5.seconds,
                ticker = { 42L },
                delayMillis = {
                    waiting.complete(Unit)
                    awaitCancellation()
                },
            )
        }

        waiting.await()
        contender.cancel(CancellationException("deadline cancellation"))

        try {
            contender.await()
            error("취소된 Hazelcast wait가 정상 완료되었습니다")
        } catch (_: CancellationException) {
            contender.isCancelled.shouldBeTrue()
        }
    }

    private fun contendedMap(): IMap<String, String> = mockk<IMap<String, String>>(relaxed = true).also { lockMap ->
        every { lockMap.putIfAbsent(any(), any(), any<Long>(), TimeUnit.MILLISECONDS) } returns "owner"
    }
}
