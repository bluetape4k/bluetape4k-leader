package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ExportingLeaderHistorySinkTest {

    private companion object: KLogging() {
        val ACQUIRED_AT: Instant = Instant.parse("2026-08-19T00:00:00Z")
        val LOCKED_UNTIL: Instant = Instant.parse("2026-08-19T00:01:00Z")
        val FINISHED_AT: Instant = Instant.parse("2026-08-19T00:00:42Z")
    }

    @Test
    fun `delegate result is preserved and history lifecycle is exported`() {
        val exporter = RecordingExporter()
        val key = LeaderHistoryKey(historyId = "history-1", lockName = "job", token = "secret")
        val delegate = RecordingSink(key)
        val sink = ExportingLeaderHistorySink(delegate, exporter)

        sink.recordAcquired(record()) shouldBeEqualTo key
        sink.recordCompleted(key, FINISHED_AT, 42)

        delegate.acquired shouldBeEqualTo 1
        delegate.completed shouldBeEqualTo 1
        exporter.events.size shouldBeEqualTo 2

        exporter.events[0].shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .status shouldBeEqualTo LeaderHistoryStatus.ACQUIRED
        exporter.events[1].shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .status shouldBeEqualTo LeaderHistoryStatus.COMPLETED

        exporter.events.joinToString() shouldNotContain "secret"
    }

    @Test
    fun `blocking delegate completion failure clears pending context`() {
        val exporter = RecordingExporter()
        val key = LeaderHistoryKey(lockName = "job", token = "secret")
        val delegate = RecordingSink(
            key = key,
            completedFailure = IllegalStateException("completed-failure"),
        )
        val sink = ExportingLeaderHistorySink(
            delegate,
            exporter,
            LeaderAuditValueSanitizer.Truncate(maxBytes = 64),
        )

        sink.recordAcquired(record()) shouldBeEqualTo key
        assertFailsWith<IllegalStateException> {
            sink.recordCompleted(key, FINISHED_AT, 42)
        }

        sink.recordFailed(key, FINISHED_AT, 43, "failed", "fallback")
        exporter.events.last().shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .attributes["audit_context"] shouldBeEqualTo "missing"
    }

    @Test
    fun `blocking delegate failure clears pending context`() {
        val exporter = RecordingExporter()
        val key = LeaderHistoryKey(lockName = "job", token = "secret")
        val delegate = RecordingSink(
            key = key,
            failedFailure = IllegalArgumentException("failed-failure"),
        )
        val sink = ExportingLeaderHistorySink(
            delegate,
            exporter,
            LeaderAuditValueSanitizer.Truncate(maxBytes = 64),
        )

        sink.recordAcquired(record()) shouldBeEqualTo key
        assertFailsWith<IllegalArgumentException> {
            sink.recordFailed(key, FINISHED_AT, 42, "failed", "failure")
        }

        sink.recordCompleted(key, FINISHED_AT, 43)
        exporter.events.last().shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .attributes["audit_context"] shouldBeEqualTo "missing"
    }

    @Test
    fun `exporter drop does not change delegate result and missing context is bounded`() {
        val exporter = RecordingExporter(LeaderAuditSubmitResult.DROPPED_QUEUE_FULL)
        val key = LeaderHistoryKey(lockName = "job", token = "secret")
        val sink = ExportingLeaderHistorySink(
            RecordingSink(key),
            exporter,
            LeaderAuditValueSanitizer.Truncate(maxBytes = 64),
        )

        sink.recordAcquired(record()) shouldBeEqualTo key
        sink.recordFailed(
            LeaderHistoryKey(lockName = "unknown", token = "unknown"),
            FINISHED_AT,
            7,
            "java.lang.IllegalStateException",
            "failed",
        )

        exporter.events.size shouldBeEqualTo 2
        exporter.events.last().shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .attributes["audit_context"] shouldBeEqualTo "missing"
    }

    @Test
    fun `direct sink bounds oversized pending metadata before terminal export`() {
        val exporter = RecordingExporter()
        val key = LeaderHistoryKey(historyId = "history-oversized", lockName = "job", token = "secret")
        val sink = ExportingLeaderHistorySink(
            RecordingSink(key),
            exporter,
            LeaderAuditValueSanitizer.Truncate(maxBytes = 10_000),
        )
        val metadata = linkedMapOf<String, String>().apply {
            repeat(LeaderLockHistoryRecord.MAX_METADATA_KEYS + 4) { index ->
                put("entry-$index", "v".repeat(1000))
            }
        }

        sink.recordAcquired(record(metadata = metadata)) shouldBeEqualTo key
        sink.recordCompleted(key, FINISHED_AT, 42)

        val acquired = exporter.events[0].shouldBeInstanceOf<LeaderAuditExportEvent.History>()
        val completed = exporter.events[1].shouldBeInstanceOf<LeaderAuditExportEvent.History>()
        completed.attributes.size shouldBeGreaterThan acquired.attributes.size
        completed.attributes["entry-0"]?.length shouldBeEqualTo LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH
    }

    @Test
    fun `suspend delegate cancellation is rethrown before export`() = runTest {
        val exporter = RecordingExporter()
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object: SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey =
                    throw CancellationException("cancelled")

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                    Unit

                override suspend fun recordFailed(
                    key: LeaderHistoryKey,
                    finishedAt: Instant,
                    durationMs: Long,
                    errorType: String?,
                    errorMessage: String?,
                ) = Unit
            },
            exporter = exporter,
        )

        assertFailsWith<CancellationException> {
            withContext(Dispatchers.Default) {
                sink.recordAcquired(record())
            }
        }
        exporter.events.shouldBeEmpty()
    }

    @Test
    fun `suspend delegate cancellation after null result is rethrown`() = runTest {
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object: SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
                    currentCoroutineContext()[Job]?.cancel()
                    return null
                }

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                    Unit

                override suspend fun recordFailed(
                    key: LeaderHistoryKey,
                    finishedAt: Instant,
                    durationMs: Long,
                    errorType: String?,
                    errorMessage: String?,
                ) = Unit
            },
            exporter = RecordingExporter(),
        )

        assertFailsWith<CancellationException> {
            withContext(Dispatchers.Default) {
                sink.recordAcquired(record())
            }
        }
    }

    @Test
    fun `suspend terminal cancellation after delegate clears pending context`() = runTest {
        val exporter = RecordingExporter()
        val key = LeaderHistoryKey(lockName = "job", token = "secret")
        var cancelCompleted = true
        var cancelFailed = true
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object: SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey = key

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
                    if (cancelCompleted) {
                        cancelCompleted = false
                        currentCoroutineContext()[Job]?.cancel()
                    }
                }

                override suspend fun recordFailed(
                    key: LeaderHistoryKey,
                    finishedAt: Instant,
                    durationMs: Long,
                    errorType: String?,
                    errorMessage: String?,
                ) {
                    if (cancelFailed) {
                        cancelFailed = false
                        currentCoroutineContext()[Job]?.cancel()
                    }
                }
            },
            exporter = exporter,
            sanitizer = LeaderAuditValueSanitizer.Truncate(maxBytes = 64),
        )

        sink.recordAcquired(record()) shouldBeEqualTo key
        assertFailsWith<CancellationException> {
            withContext(Dispatchers.Default) {
                sink.recordCompleted(key, FINISHED_AT, 42)
            }
        }
        sink.recordCompleted(key, FINISHED_AT, 43)
        exporter.events.last().shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .attributes["audit_context"] shouldBeEqualTo "missing"

        sink.recordAcquired(record()) shouldBeEqualTo key

        assertFailsWith<CancellationException> {
            withContext(Dispatchers.Default) {
                sink.recordFailed(key, FINISHED_AT, 44, "failed", "cancelled")
            }
        }

        sink.recordCompleted(key, FINISHED_AT, 45)
        exporter.events.last().shouldBeInstanceOf<LeaderAuditExportEvent.History>()
            .attributes["audit_context"] shouldBeEqualTo "missing"
    }

    @Test
    fun `suspend deleteOlderThan rechecks cancellation after delegate`() = runTest {
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object: io.bluetape4k.leader.history.SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                    Unit

                override suspend fun recordFailed(
                    key: LeaderHistoryKey,
                    finishedAt: Instant,
                    durationMs: Long,
                    errorType: String?,
                    errorMessage: String?,
                ) = Unit

                override suspend fun deleteOlderThan(cutoff: Instant, limit: Int): Int {
                    currentCoroutineContext()[Job]?.cancel()
                    return 1
                }
            },
            exporter = RecordingExporter(),
        )

        assertFailsWith<CancellationException> {
            withContext(Dispatchers.IO) {
                sink.deleteOlderThan(FINISHED_AT, 1)
            }
        }
    }

    private class RecordingSink(
        private val key: LeaderHistoryKey,
        private var completedFailure: Throwable? = null,
        private var failedFailure: Throwable? = null,
    ): LeaderHistorySink {
        var acquired = 0
        var completed = 0

        override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
            acquired++
            return key
        }

        override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
            completed++
            completedFailure?.let { failure ->
                completedFailure = null
                throw failure
            }
        }

        override fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) {
            failedFailure?.let { failure ->
                failedFailure = null
                throw failure
            }
        }
    }

    private class RecordingExporter(
        private val result: LeaderAuditSubmitResult = LeaderAuditSubmitResult.ACCEPTED,
    ): LeaderAuditExporter {
        private val _events = ConcurrentLinkedQueue<LeaderAuditExportEvent>()
        val events get() = _events.toList()
        private val closed = AtomicInteger()

        override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult {
            _events += event
            return result
        }

        override fun observe(observer: LeaderAuditExportObserver): AutoCloseable = AutoCloseable { }

        override fun snapshot(): LeaderAuditExportSnapshot = LeaderAuditExportSnapshot.create(
            queued = 0,
            inFlight = 0,
            scheduledRetries = 0,
            admitted = 0,
            accepted = events.size.toLong(),
            droppedQueueFull = 0,
            droppedClosed = 0,
            retries = 0,
            terminalFailures = 0,
            cancellations = 0,
            executorRejections = 0,
            schedulerRejections = 0,
            observerDrops = 0,
            observerRegistrationDrops = 0,
            diagnosticsFatalErrors = 0,
            diagnosticsClosed = closed.get() > 0,
            closed = closed.get() > 0,
        )

        override fun close() {
            closed.incrementAndGet()
        }
    }

    private fun record(metadata: Map<String, String> = emptyMap()): LeaderLockHistoryRecord = LeaderLockHistoryRecord(
        lockName = "job",
        token = "secret",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = ACQUIRED_AT,
        lockedUntil = LOCKED_UNTIL,
        nodeId = "node-1",
        status = LeaderHistoryStatus.ACQUIRED,
        metadata = metadata,
    )
}
