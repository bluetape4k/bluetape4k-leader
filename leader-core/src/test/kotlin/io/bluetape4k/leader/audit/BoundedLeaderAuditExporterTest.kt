package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.internal.BoundedLeaderAuditExporter
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedLeaderAuditExporterTest {

    private val schedulers = mutableListOf<ScheduledExecutorService>()

    @AfterEach
    fun tearDown() {
        schedulers.forEach { it.shutdownNow() }
    }

    @Test
    fun `queue full returns dropped without calling delivery`() {
        val executor = Executor { }
        val scheduler = scheduler()
        val calls = AtomicInteger()
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                calls.incrementAndGet()
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS)
            },
            queueCapacity = 1,
            executor = executor,
            scheduler = scheduler,
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.DROPPED_QUEUE_FULL)
        calls.get().shouldBeEqualTo(0)
        exporter.snapshot().droppedQueueFull.shouldBeEqualTo(1)
        exporter.close()
    }

    @Test
    fun `closed exporter rejects new work and snapshot is closed`() {
        val exporter = exporter(executor = Executor { })

        exporter.close()
        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.DROPPED_CLOSED)
        val snapshot = exporter.snapshot()
        snapshot.closed.shouldBeTrue()
        snapshot.droppedClosed.shouldBeEqualTo(1)
    }

    @Test
    fun `successful delivery releases admission permit`() {
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS)
            },
            executor = Executor { it.run() },
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        val snapshot = exporter.snapshot()
        snapshot.admitted.shouldBeEqualTo(0)
        snapshot.inFlight.shouldBeEqualTo(0)
        snapshot.accepted.shouldBeEqualTo(1)
        exporter.close()
    }

    @Test
    fun `retryable failure is bounded by max attempts`() {
        val attempts = AtomicInteger()
        val terminal = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                attempts.incrementAndGet()
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.RETRYABLE_FAILURE)
            },
            maxAttempts = 2,
            initialBackoff = Duration.ofNanos(1),
            executor = Executor { it.run() },
            onObservation = { if (it == LeaderAuditExportObservation.TERMINAL_FAILURE) terminal.countDown() },
        )

        exporter.submit(event())
        terminal.await(5, TimeUnit.SECONDS).shouldBeTrue()
        attempts.get().shouldBeEqualTo(2)
        exporter.snapshot().terminalFailures.shouldBeEqualTo(1)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.close()
    }

    @Test
    fun `synchronous delivery failure terminalizes without leaking in flight`() {
        val terminal = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery { throw IllegalStateException("delivery-failed") },
            maxAttempts = 1,
            executor = Executor { it.run() },
            onObservation = { if (it == LeaderAuditExportObservation.TERMINAL_FAILURE) terminal.countDown() },
        )

        exporter.submit(event())
        terminal.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.snapshot().inFlight.shouldBeEqualTo(0)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.close()
    }

    @Test
    fun `close cancels an in flight future exactly once`() {
        val future = CompletableFuture<LeaderAuditDeliveryResult>()
        val cancelled = CountDownLatch(1)
        future.whenComplete { _, failure -> if (failure is CancellationException) cancelled.countDown() }
        val exporter = exporter(
            delivery = LeaderAuditDelivery { future },
            executor = Executor { it.run() },
        )

        exporter.submit(event())
        exporter.close()
        cancelled.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.snapshot().cancellations.shouldBeEqualTo(1)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
    }

    @Test
    fun `observer receives accepted and failure observations without changing submit result`() {
        val observations = mutableListOf<LeaderAuditExportObservation>()
        val observed = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.TERMINAL_FAILURE)
            },
            executor = Executor { it.run() },
            onObservation = {
                synchronized(observations) { observations += it }
                if (it == LeaderAuditExportObservation.TERMINAL_FAILURE) observed.countDown()
            },
        )

        exporter.observe(LeaderAuditExportObserver { observedObservation ->
            synchronized(observations) { observations += observedObservation }
        })
        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        observed.await(5, TimeUnit.SECONDS).shouldBeTrue()
        synchronized(observations) {
            observations.contains(LeaderAuditExportObservation.ACCEPTED).shouldBeTrue()
            observations.contains(LeaderAuditExportObservation.TERMINAL_FAILURE).shouldBeTrue()
        }
        exporter.close()
    }

    private fun exporter(
        delivery: LeaderAuditDelivery = LeaderAuditDelivery {
            CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS)
        },
        queueCapacity: Int = 8,
        maxInFlight: Int = minOf(queueCapacity, 2),
        maxAttempts: Int = 3,
        attemptTimeout: Duration = Duration.ofSeconds(1),
        initialBackoff: Duration = Duration.ofMillis(1),
        maxBackoff: Duration = Duration.ofSeconds(1),
        executor: Executor = Executor { it.run() },
        scheduler: ScheduledExecutorService = scheduler(),
        onObservation: (LeaderAuditExportObservation) -> Unit = {},
    ): BoundedLeaderAuditExporter {
        val exporter = BoundedLeaderAuditExporter(
            delivery = delivery,
            options = LeaderAuditExportOptions(
                queueCapacity = queueCapacity,
                maxInFlight = maxInFlight,
                maxAttempts = maxAttempts,
                attemptTimeout = attemptTimeout,
                initialBackoff = initialBackoff,
                maxBackoff = maxBackoff,
                executor = executor,
                scheduler = scheduler,
            ),
        )
        exporter.observe(LeaderAuditExportObserver(onObservation))
        return exporter
    }

    private fun scheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor().also(schedulers::add)

    private fun event(): LeaderAuditExportEvent.History = LeaderAuditExportEvent.History.from(
        record = LeaderLockHistoryRecord(
            lockName = "lock",
            token = "token",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = Instant.parse("2026-08-18T00:00:00Z"),
            lockedUntil = Instant.parse("2026-08-18T00:01:00Z"),
            status = LeaderHistoryStatus.ACQUIRED,
        ),
        sanitizer = LeaderAuditValueSanitizer.Default,
    )
}
