package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.redisson.api.RFuture
import org.redisson.api.RLock
import org.redisson.api.RMapCache
import org.redisson.api.RedissonClient
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class RedissonCandidateRegistryCancellationTest {

    @Test
    fun `entry lock cancellation unlocks a late server-side acquisition`() = runSuspendIO {
        val nodeId = "node-826-late-acquire"
        val lockRequested = CompletableDeferred<Unit>()
        val lockLease = CompletableDeferred<Long>()
        val sourceCancellationRequested = CompletableDeferred<Unit>()
        val unlockRequested = CompletableDeferred<Long>()
        val acquisition = ControlledRFuture<Boolean>(ignoreCancellation = true) {
            sourceCancellationRequested.complete(Unit)
        }
        val lock = mockk<RLock>()
        val cache = mockk<RMapCache<String, CandidateInfo>>()
        val client = mockk<RedissonClient>()

        every { client.getMapCache<String, CandidateInfo>(any<String>()) } returns cache
        every { cache.getLock(nodeId) } returns lock
        every {
            lock.tryLockAsync(
                any<Long>(),
                any<Long>(),
                any<java.util.concurrent.TimeUnit>(),
                any<Long>(),
            )
        } answers {
            lockRequested.complete(Unit)
            lockLease.complete(secondArg())
            acquisition
        }
        every { lock.unlockAsync(any<Long>()) } answers {
            unlockRequested.complete(firstArg())
            completedFuture()
        }
        every { cache.putAsync(nodeId, any<CandidateInfo>()) } returns completedFuture()

        val registry = RedissonCandidateRegistry(client)
        coroutineScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                registry.registerCandidateSuspending(
                    lockName = "issue-826-late-acquire",
                    info = CandidateInfo(nodeId),
                    ttl = kotlin.time.Duration.ZERO,
                )
            }

            lockRequested.await()
            lockLease.await() shouldBeEqualTo -1L
            val cancellation = CancellationException("caller cancelled while waiting for entry lock")
            job.cancel(cancellation)
            val thrown = assertFailsWith<CancellationException> { job.await() }
            thrown.message shouldBeEqualTo cancellation.message
            withTimeout(1.seconds) { sourceCancellationRequested.await() }

            // The Redis command may complete after the cancelled coroutine has returned.
            acquisition.complete(true)
            withTimeout(1.seconds) {
                unlockRequested.await() > 0L
            } shouldBeEqualTo true
        }
    }

    @Test
    fun `non completing entry lock source is cancelled by the bounded attempt deadline`() = runSuspendIO {
        val nodeId = "node-826-source-timeout"
        val lockRequested = CompletableDeferred<Unit>()
        val sourceCancellationRequested = CompletableDeferred<Unit>()
        val acquisition = ControlledRFuture<Boolean>(ignoreCancellation = true) {
            lockRequested.complete(Unit)
            sourceCancellationRequested.complete(Unit)
        }
        val lock = mockk<RLock>()
        val cache = mockk<RMapCache<String, CandidateInfo>>()
        val client = mockk<RedissonClient>()

        every { client.getMapCache<String, CandidateInfo>(any<String>()) } returns cache
        every { cache.getLock(nodeId) } returns lock
        every {
            lock.tryLockAsync(
                any<Long>(),
                any<Long>(),
                any<java.util.concurrent.TimeUnit>(),
                any<Long>(),
            )
        } answers {
            lockRequested.complete(Unit)
            acquisition
        }

        val registry = RedissonCandidateRegistry(client)
        coroutineScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                registry.registerCandidateSuspending(
                    lockName = "issue-826-source-timeout",
                    info = CandidateInfo(nodeId),
                    ttl = kotlin.time.Duration.ZERO,
                )
            }

            lockRequested.await()
            withTimeout(3.seconds) {
                sourceCancellationRequested.await()
            }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { job.await() }
            }
            job.cancelAndJoin()
        }
    }

    @Test
    fun `entry lock cancellation does not wait indefinitely for unlock response`() = runSuspendIO {
        val nodeId = "node-826-unlock-timeout"
        val actionStarted = CompletableDeferred<Unit>()
        val acquisition = completedFuture(true)
        val action = ControlledRFuture<CandidateInfo>(ignoreCancellation = true)
        val unlock = ControlledRFuture<Void>(ignoreCancellation = true)
        val lock = mockk<RLock>()
        val cache = mockk<RMapCache<String, CandidateInfo>>()
        val client = mockk<RedissonClient>()

        every { client.getMapCache<String, CandidateInfo>(any<String>()) } returns cache
        every { cache.getLock(nodeId) } returns lock
        every {
            lock.tryLockAsync(
                any<Long>(),
                any<Long>(),
                any<java.util.concurrent.TimeUnit>(),
                any<Long>(),
            )
        } returns acquisition
        every { lock.unlockAsync(any<Long>()) } returns unlock
        every { cache.putAsync(nodeId, any<CandidateInfo>()) } answers {
            actionStarted.complete(Unit)
            action
        }

        val registry = RedissonCandidateRegistry(client)
        coroutineScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                registry.registerCandidateSuspending(
                    lockName = "issue-826-unlock-timeout",
                    info = CandidateInfo(nodeId),
                    ttl = kotlin.time.Duration.ZERO,
                )
            }

            actionStarted.await()
            val cancellation = CancellationException("caller cancelled while action was in flight")
            job.cancel(cancellation)
            try {
                withTimeout(2.seconds) {
                    val thrown = io.bluetape4k.assertions.assertFailsWith<CancellationException> {
                        job.await()
                    }
                    thrown.message shouldBeEqualTo cancellation.message
                }
            } finally {
                action.complete(CandidateInfo(nodeId))
                unlock.complete(null)
                job.cancelAndJoin()
            }
        }
    }

    private class ControlledRFuture<T>(
        private val ignoreCancellation: Boolean = false,
        private val onCancellation: () -> Unit = {},
    ) : CompletableFuture<T>(), RFuture<T> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            if (ignoreCancellation) {
                onCancellation()
                true
            } else {
                onCancellation()
                super.cancel(mayInterruptIfRunning)
            }
    }

    private fun <T> completedFuture(value: T? = null): RFuture<T> =
        ControlledRFuture<T>().also { it.complete(value) }
}
