package io.bluetape4k.leader.benchmark

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
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

    private lateinit var record: LeaderLockHistoryRecord
    private lateinit var noopRecorder: SafeLeaderHistoryRecorder
    private lateinit var inMemoryRecorder: SafeLeaderHistoryRecorder
    private lateinit var noopSuspendRecorder: SuspendSafeLeaderHistoryRecorder
    private lateinit var inMemorySuspendRecorder: SuspendSafeLeaderHistoryRecorder
    private lateinit var finishedAt: Instant

    @Setup
    fun setup() {
        val now = Instant.parse("2026-05-21T00:00:00Z")
        finishedAt = now.plusMillis(1)
        record = LeaderLockHistoryRecord(
            lockName = "jmh-history",
            token = "jmh-token",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = now,
            lockedUntil = now.plusSeconds(60),
            nodeId = "jmh-node",
            metadata = mapOf("component" to "leader-core", "scenario" to "history"),
        )
        noopRecorder = SafeLeaderHistoryRecorder(NoopLeaderHistorySink)
        inMemoryRecorder = SafeLeaderHistoryRecorder(InMemoryLeaderHistorySink())
        noopSuspendRecorder = SuspendSafeLeaderHistoryRecorder(NoopSuspendLeaderHistorySink)
        inMemorySuspendRecorder = SuspendSafeLeaderHistoryRecorder(InMemorySuspendLeaderHistorySink())
    }

    @Benchmark
    fun blockingNoopAcquireComplete(blackhole: Blackhole) {
        val key = noopRecorder.recordAcquired(record) ?: fallbackKey()
        noopRecorder.recordCompleted(key, finishedAt, 1)
        blackhole.consume(key)
    }

    @Benchmark
    fun blockingInMemoryAcquireComplete(blackhole: Blackhole) {
        val key = inMemoryRecorder.recordAcquired(record) ?: fallbackKey()
        inMemoryRecorder.recordCompleted(key, finishedAt, 1)
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
    fun suspendInMemoryAcquireComplete(blackhole: Blackhole) {
        val key = runBlocking {
            val acquired = inMemorySuspendRecorder.recordAcquired(record) ?: fallbackKey()
            inMemorySuspendRecorder.recordCompleted(acquired, finishedAt, 1)
            acquired
        }
        blackhole.consume(key)
    }

    private fun fallbackKey(): LeaderHistoryKey =
        LeaderHistoryKey(lockName = record.lockName, token = record.token)

    private class InMemoryLeaderHistorySink: LeaderHistorySink {
        private val records = ConcurrentHashMap<LeaderHistoryKey, LeaderLockHistoryRecord>()

        override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
            val key = LeaderHistoryKey(lockName = record.lockName, token = record.token)
            records[key] = record
            return key
        }

        override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
            records.computeIfPresent(key) { _, current ->
                current.withTerminalStatus(finishedAt, durationMs)
            }
        }

        override fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) {
            records.remove(key)
        }
    }

    private class InMemorySuspendLeaderHistorySink: SuspendLeaderHistorySink {
        private val records = ConcurrentHashMap<LeaderHistoryKey, LeaderLockHistoryRecord>()

        override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey {
            val key = LeaderHistoryKey(lockName = record.lockName, token = record.token)
            records[key] = record
            return key
        }

        override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
            records.computeIfPresent(key) { _, current ->
                current.withTerminalStatus(finishedAt, durationMs)
            }
        }

        override suspend fun recordFailed(
            key: LeaderHistoryKey,
            finishedAt: Instant,
            durationMs: Long,
            errorType: String?,
            errorMessage: String?,
        ) {
            records.remove(key)
        }
    }

    private companion object {
        fun LeaderLockHistoryRecord.withTerminalStatus(
            finishedAt: Instant,
            durationMs: Long,
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
                status = io.bluetape4k.leader.history.LeaderHistoryStatus.COMPLETED,
                slotId = slotId,
                metadata = metadata,
            )
    }
}
