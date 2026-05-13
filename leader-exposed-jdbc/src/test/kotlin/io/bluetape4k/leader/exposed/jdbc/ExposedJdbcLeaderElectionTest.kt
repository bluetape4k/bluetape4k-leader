package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.history.ExposedLeaderHistorySink
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExposedJdbcLeaderElectionTest: AbstractExposedJdbcLeaderTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderElector(db)

        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - blank lockName은 IllegalArgumentException을 발생시킨다`(testDB: TestDB) {
        val db = connectDb(testDB)
        val election = ExposedJdbcLeaderElector(db)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("   ") { }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 255자 초과 lockName은 IllegalArgumentException을 발생시킨다`(testDB: TestDB) {
        val db = connectDb(testDB)
        val election = ExposedJdbcLeaderElector(db)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("x".repeat(256)) { }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 락 보유 중 짧은 waitTime으로 호출하면 null을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val holderLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        holderLock.tryLock(1.seconds, 30.seconds)

        try {
            val shortOptions = ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 100.milliseconds,
                    leaseTime = 5.seconds,
                )
            )
            val election = ExposedJdbcLeaderElector(db, shortOptions)
            val result = election.runIfLeader(lockName) { "실행하면 안 됨" }

            result.shouldBeNull()
        } finally {
            holderLock.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - leaseTime 만료 후 takeover가 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val leaseTime = 200.milliseconds
        val holderLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        holderLock.tryLock(1.seconds, leaseTime)

        // leaseTime(200ms) 만료를 확실히 넘기기 위해 1.5배 + buffer(50ms) 대기
        val waitForExpiryMillis = (leaseTime.inWholeMilliseconds * 3 / 2) + 50
        Thread.sleep(waitForExpiryMillis)

        val election = ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 2.seconds,
                    leaseTime = 10.seconds,
                )
            )
        )
        val result = election.runIfLeader(lockName) { "takeover 성공" }
        result shouldBeEqualTo "takeover 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 예외 발생 시 null을 반환하고 락 행이 삭제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(db)

        // Breaking change: action exceptions are now swallowed and null is returned (issue #50)
        val result = election.runIfLeader(lockName) {
            throw LeaderElectionException("테스트 예외")
        }
        result shouldBeNull()

        val rowCount = transaction(db) {
            LeaderLockTable.selectAll()
                .where { LeaderLockTable.lockName eq lockName }
                .count()
        }
        rowCount shouldBeEqualTo 0L
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 예외 발생 후 락이 해제되어 다음 호출이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(db)

        runCatching { election.runIfLeader(lockName) { throw LeaderElectionException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - CancellationException은 재전파되고 이력에 기록되지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val sink = ExposedLeaderHistorySink(db)
        val recorder = SafeLeaderHistoryRecorder(sink)
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            )
        )
        val election = ExposedJdbcLeaderElector(db, options, recorder)

        assertFailsWith<CancellationException> {
            election.runIfLeader(lockName) {
                throw CancellationException("취소 테스트")
            }
        }

        // CancellationException은 FAILED로 기록되지 않아야 함
        val historyCount = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where {
                    (LeaderLockHistoryTable.lockName eq lockName) and
                            (LeaderLockHistoryTable.status eq HistoryStatus.FAILED.name)
                }
                .count()
        }
        historyCount shouldBeEqualTo 0L
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - historyRecorder 사용 시 ACQUIRED 및 COMPLETED 이력이 기록된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val sink = ExposedLeaderHistorySink(db)
        val recorder = SafeLeaderHistoryRecorder(sink)
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            )
        )
        val election = ExposedJdbcLeaderElector(db, options, recorder)

        election.runIfLeader(lockName) { "done" }

        val rows = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .toList()
        }
        rows.size shouldBeEqualTo 1
        rows[0][LeaderLockHistoryTable.status] shouldBeEqualTo HistoryStatus.COMPLETED.name
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - historyRecorder 사용 시 action 실패 후 FAILED 이력이 기록된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val sink = ExposedLeaderHistorySink(db)
        val recorder = SafeLeaderHistoryRecorder(sink)
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            )
        )
        val election = ExposedJdbcLeaderElector(db, options, recorder)

        // Breaking change: action exceptions are now swallowed (null returned)
        val result = election.runIfLeader(lockName) { throw LeaderElectionException("fail") }
        result shouldBeNull()

        val rows = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .toList()
        }
        rows.size shouldBeEqualTo 1
        rows[0][LeaderLockHistoryTable.status] shouldBeEqualTo HistoryStatus.FAILED.name
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 동일 lockName에 여러 스레드 동시 접근 시 최소 1개 이상 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 5.seconds,
                leaseTime = 10.seconds,
            )
        )
        val election = ExposedJdbcLeaderElector(db, options)
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(8)
            .rounds(1)
            .add {
                election.runIfLeader(lockName) {
                    Thread.sleep(10)
                    successCount.incrementAndGet()
                }
                log.debug { "successCount=${successCount.get()}" }
            }
            .run()

        successCount.get() shouldBeGreaterOrEqualTo 1
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderElector(db)

        val result = election.runAsyncIfLeader(randomName(), VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action이 CF 반환 전 throw하면 null을 반환하고 락이 해제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(db)

        // Breaking change: sync action exception → null (not CompletionException) (issue #50)
        val result = election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
            throw IllegalStateException("action 동기 예외")
        }.get(5, TimeUnit.SECONDS)
        result shouldBeNull()

        // 락이 해제되어 다음 호출이 성공해야 함
        val next = election.runIfLeader(lockName) { "복구 성공" }
        next shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action이 failedFuture 반환 시 FAILED 이력 기록 후 락 해제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val sink = ExposedLeaderHistorySink(db)
        val recorder = SafeLeaderHistoryRecorder(sink)
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            ),
        )
        val election = ExposedJdbcLeaderElector(db, options, recorder)

        // failedFuture case: the future itself fails → CompletionException propagates
        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                CompletableFuture.failedFuture(IllegalStateException("async 실패"))
            }.join()
        }

        // 다음 호출이 성공해야 함 (락 해제됨)
        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"

        // FAILED 이력 1건 확인
        val failedCount = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where {
                    (LeaderLockHistoryTable.lockName eq lockName) and
                            (LeaderLockHistoryTable.status eq HistoryStatus.FAILED.name)
                }
                .count()
        }
        failedCount shouldBeEqualTo 1L
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 정상 완료 후 락 행이 삭제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(db)

        election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            futureOf { "ok" }
        }.get(5, TimeUnit.SECONDS) shouldBeEqualTo "ok"

        val rowCount = transaction(db) {
            LeaderLockTable.selectAll()
                .where { LeaderLockTable.lockName eq lockName }
                .count()
        }
        rowCount shouldBeEqualTo 0L
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `ensureSchema - resetFor 후 재호출 시 에러 없이 완료된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer.resetFor(db)

        io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer.ensureSchema(db)

        db.shouldNotBeNull()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `Database 확장함수 runIfLeader - 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val result = db.runIfLeader(randomName()) { "ext 성공" }

        result shouldBeEqualTo "ext 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `Database 확장함수 runAsyncIfLeader - 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val result = db.runAsyncIfLeader(randomName()) {
            futureOf { "async ext 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async ext 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `Database 확장함수 runVirtualIfLeader - 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val result = db.runVirtualIfLeader(randomName()) { "virtual ext 성공" }[5, TimeUnit.SECONDS]

        result shouldBeEqualTo "virtual ext 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `VirtualThread 선출 - runAsyncIfLeader 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderElector(db)
        val vtElection = ExposedJdbcVirtualThreadLeaderElector(election)

        val result = vtElection.runAsyncIfLeader(randomName()) { "vt 성공" }[5, TimeUnit.SECONDS]

        result shouldBeEqualTo "vt 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `VirtualThread 선출 - 락 보유 중 실패 시 null을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val holderLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        holderLock.tryLock(1.seconds, 30.seconds)

        try {
            val shortOptions = ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 100.milliseconds,
                    leaseTime = 5.seconds,
                )
            )
            val election = ExposedJdbcLeaderElector(db, shortOptions)
            val vtElection = ExposedJdbcVirtualThreadLeaderElector(election)
            val result = vtElection.runAsyncIfLeader(lockName) { "실행하면 안 됨" }
                .get(5, TimeUnit.SECONDS)

            result.shouldBeNull()
        } finally {
            holderLock.unlock()
        }
    }
}
