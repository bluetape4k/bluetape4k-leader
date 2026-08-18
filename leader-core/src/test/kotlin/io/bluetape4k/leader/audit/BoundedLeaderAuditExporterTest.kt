package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val delivered = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                delivered.countDown()
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS)
            },
            executor = Executor { it.run() },
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        delivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        awaitAdmissionReleased(exporter)
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
    fun `close winning before synchronous delivery failure releases permit exactly once`() {
        val deliveryStarted = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        val deliveryFailed = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                deliveryStarted.countDown()
                releaseDelivery.await(5, TimeUnit.SECONDS).shouldBeTrue()
                deliveryFailed.countDown()
                throw IllegalStateException("late-delivery-failure")
            },
            maxAttempts = 1,
            executor = Executor { it.run() },
        )

        val submitReturned = CountDownLatch(1)
        Thread.ofVirtual().start {
            exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
            submitReturned.countDown()
        }
        submitReturned.await(5, TimeUnit.SECONDS).shouldBeTrue()
        deliveryStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val closeReturned = CountDownLatch(1)
        Thread.ofVirtual().start {
            exporter.close()
            closeReturned.countDown()
        }
        closeReturned.await(1, TimeUnit.SECONDS).shouldBeTrue()
        releaseDelivery.countDown()
        deliveryFailed.await(5, TimeUnit.SECONDS).shouldBeTrue()

        val snapshot = exporter.snapshot()
        snapshot.cancellations.shouldBeEqualTo(1)
        snapshot.inFlight.shouldBeEqualTo(0)
        snapshot.admitted.shouldBeEqualTo(0)
    }

    @Test
    fun `close cancels an in flight future exactly once`() {
        val future = CompletableFuture<LeaderAuditDeliveryResult>()
        val deliveryStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        future.whenComplete { _, failure -> if (failure is CancellationException) cancelled.countDown() }
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                deliveryStarted.countDown()
                future
            },
            executor = Executor { it.run() },
        )

        exporter.submit(event())
        deliveryStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.close()
        cancelled.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.snapshot().cancellations.shouldBeEqualTo(1)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
    }

    @Test
    fun `hung delivery times out and retries up to the configured attempt limit`() {
        val attempts = AtomicInteger()
        val terminal = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                attempts.incrementAndGet()
                CompletableFuture()
            },
            maxAttempts = 2,
            attemptTimeout = Duration.ofMillis(10),
            initialBackoff = Duration.ofNanos(1),
            onObservation = { if (it == LeaderAuditExportObservation.TERMINAL_FAILURE) terminal.countDown() },
            executor = Executor { it.run() },
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        terminal.await(5, TimeUnit.SECONDS).shouldBeTrue()
        attempts.get().shouldBeEqualTo(2)
        exporter.snapshot().retries.shouldBeEqualTo(1)
        exporter.snapshot().inFlight.shouldBeEqualTo(0)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.close()
    }

    @Test
    fun `in flight saturation waits for release without recursive worker spin`() {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val futures = ConcurrentLinkedQueue<CompletableFuture<LeaderAuditDeliveryResult>>()
        val exporter = exporter(
            queueCapacity = 3,
            maxInFlight = 1,
            delivery = LeaderAuditDelivery {
                CompletableFuture<LeaderAuditDeliveryResult>().also {
                    futures += it
                    if (calls.incrementAndGet() == 1) firstStarted.countDown() else secondStarted.countDown()
                }
            },
            executor = Executor { it.run() },
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        firstStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        futures.remove().complete(LeaderAuditDeliveryResult.SUCCESS)
        secondStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.snapshot().inFlight.shouldBeEqualTo(1)
        futures.remove().complete(LeaderAuditDeliveryResult.SUCCESS)
        exporter.close()
        exporter.snapshot().admitted.shouldBeEqualTo(0)
    }

    @Test
    fun `worker handoff retries after inline executor exits with queued work`() {
        val firstFuture = CompletableFuture<LeaderAuditDeliveryResult>()
        val firstStarted = CountDownLatch(1)
        val allowFirstReturn = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val releaseExecutor = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executor { command ->
            command.run()
            workerExited.countDown()
            releaseExecutor.await(5, TimeUnit.SECONDS).shouldBeTrue()
        }
        val exporter = exporter(
            queueCapacity = 2,
            maxInFlight = 1,
            delivery = LeaderAuditDelivery {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    allowFirstReturn.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    firstFuture
                } else {
                    secondStarted.countDown()
                    CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS)
                }
            },
            executor = executor,
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        firstStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        allowFirstReturn.countDown()
        workerExited.await(5, TimeUnit.SECONDS).shouldBeTrue()

        firstFuture.complete(LeaderAuditDeliveryResult.SUCCESS).shouldBeTrue()
        releaseExecutor.countDown()
        secondStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        awaitAdmissionReleased(exporter)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.close()
    }

    @Test
    fun `executor rejection releases all permits and later submissions recover`() {
        val rejectFirst = AtomicBoolean(true)
        val rejected = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val deliveryFuture = CompletableFuture<LeaderAuditDeliveryResult>()
        val executor = Executor { command ->
            if (rejectFirst.getAndSet(false)) throw RejectedExecutionException("first")
            command.run()
        }
        val exporter = exporter(
            queueCapacity = 2,
            delivery = LeaderAuditDelivery {
                recovered.countDown()
                deliveryFuture
            },
            executor = executor,
            onObservation = { if (it == LeaderAuditExportObservation.EXECUTOR_REJECTED) rejected.countDown() },
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        rejected.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        recovered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        deliveryFuture.complete(LeaderAuditDeliveryResult.SUCCESS).shouldBeTrue()
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.snapshot().executorRejections.shouldBeEqualTo(1)
        exporter.close()
    }

    @Test
    fun `direct executor cannot make submit wait for blocking delivery`() {
        val deliveryStarted = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        val submitReturned = CountDownLatch(1)
        val exporter = exporter(
            delivery = LeaderAuditDelivery {
                deliveryStarted.countDown()
                releaseDelivery.await(5, TimeUnit.SECONDS).shouldBeTrue()
                CompletableFuture.completedFuture(LeaderAuditDeliveryResult.SUCCESS)
            },
            executor = Executor { it.run() },
        )

        Thread.ofVirtual().start {
            exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
            submitReturned.countDown()
        }
        submitReturned.await(5, TimeUnit.SECONDS).shouldBeTrue()
        deliveryStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        val closeReturned = CountDownLatch(1)
        Thread.ofVirtual().start {
            exporter.close()
            closeReturned.countDown()
        }
        closeReturned.await(1, TimeUnit.SECONDS).shouldBeTrue()
        releaseDelivery.countDown()
    }

    @Test
    fun `exceptional delivery Error reaches uncaught boundary after cleanup`() {
        val uncaught = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            if (error is AssertionError) uncaught.countDown()
        }
        try {
            val future = CompletableFuture<LeaderAuditDeliveryResult>()
            val deliveryStarted = CountDownLatch(1)
            val exporter = exporter(
                delivery = LeaderAuditDelivery {
                    deliveryStarted.countDown()
                    future
                },
                executor = Executor { it.run() },
                maxAttempts = 1,
            )
            exporter.submit(event())
            deliveryStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            future.completeExceptionally(AssertionError("delivery-error"))
            uncaught.await(5, TimeUnit.SECONDS).shouldBeTrue()
            exporter.snapshot().admitted.shouldBeEqualTo(0)
            exporter.close()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `scheduler rejection is counted once and terminalizes the attempt`() {
        val rejected = CountDownLatch(1)
        val scheduler = scheduler().also { it.shutdownNow() }
        val exporter = exporter(
            scheduler = scheduler,
            executor = Executor { it.run() },
            onObservation = { if (it == LeaderAuditExportObservation.SCHEDULER_REJECTED) rejected.countDown() },
        )

        exporter.submit(event()).shouldBeEqualTo(LeaderAuditSubmitResult.ACCEPTED)
        rejected.await(5, TimeUnit.SECONDS).shouldBeTrue()
        exporter.snapshot().schedulerRejections.shouldBeEqualTo(1)
        exporter.snapshot().admitted.shouldBeEqualTo(0)
        exporter.close()
    }

    @Test
    fun `invalid options fail fast at every bounded boundary`() {
        val executor = Executor { }
        val scheduler = scheduler()

        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 0,
                maxInFlight = 1,
                maxAttempts = 1,
                attemptTimeout = Duration.ofSeconds(1),
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 1,
                maxInFlight = 2,
                maxAttempts = 1,
                attemptTimeout = Duration.ofSeconds(1),
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 1,
                maxInFlight = 1,
                maxAttempts = 17,
                attemptTimeout = Duration.ofSeconds(1),
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 1,
                maxInFlight = 1,
                maxAttempts = 1,
                attemptTimeout = Duration.ZERO,
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 1,
                maxInFlight = 1,
                maxAttempts = 1,
                attemptTimeout = Duration.ofSeconds(1),
                initialBackoff = Duration.ZERO,
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 1,
                maxInFlight = 1,
                maxAttempts = 1,
                attemptTimeout = Duration.ofSeconds(1),
                initialBackoff = Duration.ofSeconds(2),
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderAuditExportOptions(
                queueCapacity = 1,
                maxInFlight = 1,
                maxAttempts = 1,
                attemptTimeout = Duration.ofMinutes(6),
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofSeconds(1),
                executor = executor,
                scheduler = scheduler,
            )
        }
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

    private fun awaitAdmissionReleased(exporter: BoundedLeaderAuditExporter) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (exporter.snapshot().admitted != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
    }
}
