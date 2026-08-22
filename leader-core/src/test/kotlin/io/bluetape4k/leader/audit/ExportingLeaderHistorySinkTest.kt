package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.LeaderHistoryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ExportingLeaderHistorySinkTest {

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
        (exporter.events[0] as LeaderAuditExportEvent.History).status shouldBeEqualTo LeaderHistoryStatus.ACQUIRED
        (exporter.events[1] as LeaderAuditExportEvent.History).status shouldBeEqualTo LeaderHistoryStatus.COMPLETED
        exporter.events.joinToString().contains("secret").not().shouldBeTrue()
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
        (exporter.events.last() as LeaderAuditExportEvent.History)
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

        val acquired = exporter.events[0] as LeaderAuditExportEvent.History
        val completed = exporter.events[1] as LeaderAuditExportEvent.History
        (completed.attributes.size > acquired.attributes.size).shouldBeTrue()
        completed.attributes["entry-0"]?.length shouldBeEqualTo LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH
    }

    @Test
    fun `suspend delegate cancellation is rethrown before export`() = runTest {
        val exporter = RecordingExporter()
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object : io.bluetape4k.leader.history.SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                    throw CancellationException("cancelled")

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit

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
            withContext(Job()) {
                sink.recordAcquired(record())
            }
        }
        exporter.events.size shouldBeEqualTo 0
    }

    @Test
    fun `suspend delegate cancellation after null result is rethrown`() = runTest {
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object : io.bluetape4k.leader.history.SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
                    currentCoroutineContext()[Job]?.cancel()
                    return null
                }

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit

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
            withContext(Job()) {
                sink.recordAcquired(record())
            }
        }
    }

    @Test
    fun `suspend deleteOlderThan rechecks cancellation after delegate`() = runTest {
        val sink = ExportingSuspendLeaderHistorySink(
            delegate = object : io.bluetape4k.leader.history.SuspendLeaderHistorySink {
                override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null

                override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit

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
            withContext(Job()) {
                sink.deleteOlderThan(FINISHED_AT, 1)
            }
        }
    }

    private class RecordingSink(private val key: LeaderHistoryKey) : LeaderHistorySink {
        var acquired = 0
        var completed = 0

        override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
            acquired++
            return key
        }

        override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
            completed++
        }

        override fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) = Unit
    }

    private class RecordingExporter(
        private val result: LeaderAuditSubmitResult = LeaderAuditSubmitResult.ACCEPTED,
    ) : LeaderAuditExporter {
        val events = mutableListOf<LeaderAuditExportEvent>()
        private val closed = AtomicInteger()

        override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult {
            events += event
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

    private companion object {
        val ACQUIRED_AT: Instant = Instant.parse("2026-08-19T00:00:00Z")
        val LOCKED_UNTIL: Instant = Instant.parse("2026-08-19T00:01:00Z")
        val FINISHED_AT: Instant = Instant.parse("2026-08-19T00:00:42Z")
    }
}
