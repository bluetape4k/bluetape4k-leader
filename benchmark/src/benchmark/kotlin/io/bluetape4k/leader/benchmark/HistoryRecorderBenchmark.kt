package io.bluetape4k.leader.benchmark

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.MicrometerSafeLeaderHistoryRecorder
import io.bluetape4k.leader.micrometer.history.MicrometerSuspendSafeLeaderHistoryRecorder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
class HistoryRecorderBenchmark {

    @Param("empty", "small", "large")
    lateinit var metadataMode: String

    private lateinit var record: LeaderLockHistoryRecord
    private lateinit var noopRecorder: SafeLeaderHistoryRecorder
    private lateinit var inMemoryRecorder: SafeLeaderHistoryRecorder
    private lateinit var micrometerRecorder: SafeLeaderHistoryRecorder
    private lateinit var noopSuspendRecorder: SuspendSafeLeaderHistoryRecorder
    private lateinit var inMemorySuspendRecorder: SuspendSafeLeaderHistoryRecorder
    private lateinit var micrometerSuspendRecorder: SuspendSafeLeaderHistoryRecorder
    private lateinit var finishedAt: Instant
    private lateinit var failure: RuntimeException

    @Setup
    fun setup() {
        val now = Instant.parse("2026-05-21T00:00:00Z")
        finishedAt = now.plusMillis(1)
        failure = RuntimeException("benchmark failure for history recorder")
        record = LeaderLockHistoryRecord(
            lockName = "jmh-history",
            token = "jmh-token",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = now,
            lockedUntil = now.plusSeconds(60),
            nodeId = "jmh-node",
            metadata = metadataFor(metadataMode),
        )
        noopRecorder = SafeLeaderHistoryRecorder(NoopLeaderHistorySink)
        inMemoryRecorder = SafeLeaderHistoryRecorder(InMemoryLeaderHistorySink())
        micrometerRecorder = MicrometerSafeLeaderHistoryRecorder(
            InMemoryLeaderHistorySink(),
            SimpleMeterRegistry(),
        )
        noopSuspendRecorder = SuspendSafeLeaderHistoryRecorder(NoopSuspendLeaderHistorySink)
        inMemorySuspendRecorder = SuspendSafeLeaderHistoryRecorder(InMemorySuspendLeaderHistorySink())
        micrometerSuspendRecorder = MicrometerSuspendSafeLeaderHistoryRecorder(
            InMemorySuspendLeaderHistorySink(),
            SimpleMeterRegistry(),
        )
    }

    @Benchmark
    fun blockingNoopAcquireComplete(blackhole: Blackhole) {
        val key = noopRecorder.recordAcquired(record) ?: fallbackKey()
        noopRecorder.recordCompleted(key, finishedAt, 1)
        blackhole.consume(key)
    }

    @Benchmark
    fun blockingNoopAcquireFailed(blackhole: Blackhole) {
        val key = noopRecorder.recordAcquired(record) ?: fallbackKey()
        noopRecorder.recordFailed(key, finishedAt, 1, failure)
        blackhole.consume(key)
    }

    @Benchmark
    fun blockingInMemoryAcquireComplete(blackhole: Blackhole) {
        val key = inMemoryRecorder.recordAcquired(record) ?: fallbackKey()
        inMemoryRecorder.recordCompleted(key, finishedAt, 1)
        blackhole.consume(key)
    }

    @Benchmark
    fun blockingInMemoryAcquireFailed(blackhole: Blackhole) {
        val key = inMemoryRecorder.recordAcquired(record) ?: fallbackKey()
        inMemoryRecorder.recordFailed(key, finishedAt, 1, failure)
        blackhole.consume(key)
    }

    @Benchmark
    fun blockingMicrometerAcquireComplete(blackhole: Blackhole) {
        val key = micrometerRecorder.recordAcquired(record) ?: fallbackKey()
        micrometerRecorder.recordCompleted(key, finishedAt, 1)
        blackhole.consume(key)
    }

    @Benchmark
    fun blockingMicrometerAcquireFailed(blackhole: Blackhole) {
        val key = micrometerRecorder.recordAcquired(record) ?: fallbackKey()
        micrometerRecorder.recordFailed(key, finishedAt, 1, failure)
        blackhole.consume(key)
    }

    @Benchmark
    fun suspendNoopAcquireComplete(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = noopSuspendRecorder.recordAcquired(record) ?: fallbackKey()
            noopSuspendRecorder.recordCompleted(acquired, finishedAt, 1)
            acquired
        }
        blackhole.consume(key)
    }

    @Benchmark
    fun suspendNoopAcquireFailed(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = noopSuspendRecorder.recordAcquired(record) ?: fallbackKey()
            noopSuspendRecorder.recordFailed(acquired, finishedAt, 1, failure)
            acquired
        }
        blackhole.consume(key)
    }

    @Benchmark
    fun suspendInMemoryAcquireComplete(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = inMemorySuspendRecorder.recordAcquired(record) ?: fallbackKey()
            inMemorySuspendRecorder.recordCompleted(acquired, finishedAt, 1)
            acquired
        }
        blackhole.consume(key)
    }

    @Benchmark
    fun suspendInMemoryAcquireFailed(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = inMemorySuspendRecorder.recordAcquired(record) ?: fallbackKey()
            inMemorySuspendRecorder.recordFailed(acquired, finishedAt, 1, failure)
            acquired
        }
        blackhole.consume(key)
    }

    @Benchmark
    fun suspendMicrometerAcquireComplete(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = micrometerSuspendRecorder.recordAcquired(record) ?: fallbackKey()
            micrometerSuspendRecorder.recordCompleted(acquired, finishedAt, 1)
            acquired
        }
        blackhole.consume(key)
    }

    @Benchmark
    fun suspendMicrometerAcquireFailed(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = micrometerSuspendRecorder.recordAcquired(record) ?: fallbackKey()
            micrometerSuspendRecorder.recordFailed(acquired, finishedAt, 1, failure)
            acquired
        }
        blackhole.consume(key)
    }

    private fun fallbackKey(): LeaderHistoryKey =
        LeaderHistoryKey(lockName = record.lockName, token = record.token)

    private class InMemoryLeaderHistorySink : LeaderHistorySink {
        private val records = ConcurrentHashMap<LeaderHistoryKey, LeaderLockHistoryRecord>()

        override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
            val key = LeaderHistoryKey(lockName = record.lockName, token = record.token)
            records[key] = record
            return key
        }

        override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
            records.computeIfPresent(key) { _, current ->
                current.withTerminalStatus(
                    status = LeaderHistoryStatus.COMPLETED,
                    finishedAt = finishedAt,
                    durationMs = durationMs,
                )
            }
        }

        override fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) {
            records.computeIfPresent(key) { _, current ->
                current.withTerminalStatus(
                    status = LeaderHistoryStatus.FAILED,
                    finishedAt = finishedAt,
                    durationMs = durationMs,
                    errorType = errorType,
                    errorMessage = errorMessage,
                )
            }
        }
    }

    private class InMemorySuspendLeaderHistorySink : SuspendLeaderHistorySink {
        private val records = ConcurrentHashMap<LeaderHistoryKey, LeaderLockHistoryRecord>()

        override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
            val key = LeaderHistoryKey(lockName = record.lockName, token = record.token)
            records[key] = record
            return key
        }

        override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
            records.computeIfPresent(key) { _, current ->
                current.withTerminalStatus(
                    status = LeaderHistoryStatus.COMPLETED,
                    finishedAt = finishedAt,
                    durationMs = durationMs,
                )
            }
        }

        override suspend fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) {
            records.computeIfPresent(key) { _, current ->
                current.withTerminalStatus(
                    status = LeaderHistoryStatus.FAILED,
                    finishedAt = finishedAt,
                    durationMs = durationMs,
                    errorType = errorType,
                    errorMessage = errorMessage,
                )
            }
        }
    }

    private companion object {
        private const val LARGE_METADATA_VALUE = "0123456789abcdef0123456789abcdef"

        fun metadataFor(mode: String): Map<String, String> =
            when (mode) {
                "empty" -> emptyMap()
                "small" -> mapOf("component" to "leader-core", "scenario" to "history")
                "large" -> (1..LeaderLockHistoryRecord.MAX_METADATA_KEYS).associate { index ->
                    val key = "field${index.toString().padStart(2, '0')}"
                    key to "$LARGE_METADATA_VALUE-$index"
                }
                else -> error("Unsupported metadataMode=$mode")
            }

        fun LeaderLockHistoryRecord.withTerminalStatus(
            status: LeaderHistoryStatus,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String? = null,
            errorMessage: String? = null,
        ): LeaderLockHistoryRecord =
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = kind,
                acquiredAt = acquiredAt,
                lockedUntil = lockedUntil,
                nodeId = nodeId,
                finishedAt = finishedAt,
                durationMs = durationMs,
                status = status,
                errorType = errorType,
                errorMessage = errorMessage,
                slotId = slotId,
                metadata = metadata,
            )
    }
}
