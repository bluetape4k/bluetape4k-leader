package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class LeaderElectionResourceRegistryTest {

    @Test
    fun `close는 등록 역순으로 각 resource를 한 번만 닫는다`() = runSuspendIO {
        val closed = mutableListOf<String>()
        val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
        registry.register(TrackingCloseable("first", closed))
        registry.register(TrackingCloseable("second", closed))

        registry.close()
        registry.close()
        registry.awaitClosed()

        closed shouldBeEqualTo listOf("second", "first")
        registry.lastShutdownReport shouldBeEqualTo
            LeaderElectionShutdownReport(
                attempted = 2,
                closed = 2,
                failures = 0,
                timedOutJobs = 0,
                failureKinds = emptyMap(),
                timeoutKinds = emptyMap(),
            )
    }

    @Test
    fun `닫힌 registry에 등록하면 resource가 즉시 닫힌다`() = runSuspendIO {
        val closed = AtomicInteger(0)
        val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
        registry.close()

        registry.register(AutoCloseable { closed.incrementAndGet() }).close()

        closed.get() shouldBeEqualTo 1
    }

    @Test
    fun `job resource는 cancel 후 bounded join하고 timeout을 집계한다`() = runSuspendIO {
        val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 25.milliseconds)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(250.milliseconds) }
            }
        }
        started.await()
        registry.register(job)

        registry.close()
        registry.awaitClosed()

        job.isCancelled.shouldBeTrue()
        registry.lastShutdownReport?.timedOutJobs shouldBeEqualTo 1
        job.cancelAndJoin()
    }

    @Test
    fun `register와 close 경합은 resource를 누락하거나 두 번 닫지 않는다`() = runSuspendIO {
        val gate = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
        val pool = Executors.newFixedThreadPool(3)
        val registerJob = pool.submit {
            gate.await()
            registry.register(AutoCloseable { closeCount.incrementAndGet() })
        }
        val closeJob = pool.submit {
            gate.await()
            registry.close()
        }
        val tokenJob = pool.submit {
            gate.await()
            registry.register(AutoCloseable { closeCount.incrementAndGet() }).close()
        }
        gate.countDown()
        registerJob.get()
        closeJob.get()
        tokenJob.get()
        pool.shutdownNow()
        registry.awaitClosed()

        closeCount.get() shouldBeEqualTo 2
    }

    @Test
    fun `resource close 예외는 다음 resource를 막지 않고 kind별로 집계한다`() = runSuspendIO {
        val closed = mutableListOf<String>()
        val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
        registry.register(AutoCloseable { error("close failure") })
        registry.register(TrackingCloseable("survivor", closed))

        registry.close()
        val report = registry.awaitClosed()

        closed shouldBeEqualTo listOf("survivor")
        report shouldBeEqualTo
            LeaderElectionShutdownReport(
                attempted = 2,
                closed = 1,
                failures = 1,
                timedOutJobs = 0,
                failureKinds = mapOf("resource" to 1),
                timeoutKinds = emptyMap(),
            )
    }

    @Test
    fun `registration token을 두 번 닫아도 resource는 한 번만 닫힌다`() = runSuspendIO {
        val closeCount = AtomicInteger(0)
        val registry = LeaderElectionResourceRegistryImpl(jobJoinTimeout = 50.milliseconds)
        val token = registry.register(AutoCloseable { closeCount.incrementAndGet() })

        token.close()
        token.close()
        registry.close()
        registry.awaitClosed()

        closeCount.get() shouldBeEqualTo 1
    }

    private class TrackingCloseable(
        private val label: String,
        private val closed: MutableList<String>,
    ) : AutoCloseable {
        private val once = AtomicBoolean()

        override fun close() {
            if (once.compareAndSet(false, true)) closed += label
        }
    }
}
