package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExposedJdbcLeaderElectionTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderElection(db)

        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - blank lockName은 IllegalArgumentException을 발생시킨다`(testDB: TestDB) {
        val db = connectDb(testDB)
        val election = ExposedJdbcLeaderElection(db)

        assertThrows<IllegalArgumentException> {
            election.runIfLeader("   ") { }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 255자 초과 lockName은 IllegalArgumentException을 발생시킨다`(testDB: TestDB) {
        val db = connectDb(testDB)
        val election = ExposedJdbcLeaderElection(db)

        assertThrows<IllegalArgumentException> {
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
        holderLock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30))

        try {
            val shortOptions = ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ofMillis(100),
                    leaseTime = Duration.ofSeconds(5),
                )
            )
            val election = ExposedJdbcLeaderElection(db, shortOptions)
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

        val holderLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        holderLock.tryLock(Duration.ofSeconds(1), Duration.ofMillis(200))

        Thread.sleep(350)

        val election = ExposedJdbcLeaderElection(
            db,
            ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ofSeconds(2),
                    leaseTime = Duration.ofSeconds(10),
                )
            )
        )
        val result = election.runIfLeader(lockName) { "takeover 성공" }
        result shouldBeEqualTo "takeover 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 예외 발생 시 예외가 전파되고 락 행이 삭제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElection(db)

        assertThrows<RuntimeException> {
            election.runIfLeader(lockName) {
                throw RuntimeException("테스트 예외")
            }
        }

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
        val election = ExposedJdbcLeaderElection(db)

        runCatching { election.runIfLeader(lockName) { throw RuntimeException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - CancellationException은 재전파되고 이력에 기록되지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderElectionOptions(
            recordHistory = true,
            leaderOptions = LeaderElectionOptions(
                waitTime = Duration.ofSeconds(2),
                leaseTime = Duration.ofSeconds(10),
            )
        )
        val election = ExposedJdbcLeaderElection(db, options)

        assertThrows<CancellationException> {
            election.runIfLeader(lockName) {
                throw CancellationException("취소 테스트")
            }
        }

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
    fun `runIfLeader - recordHistory=true 시 ACQUIRED 및 COMPLETED 이력이 기록된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderElectionOptions(
            recordHistory = true,
            leaderOptions = LeaderElectionOptions(
                waitTime = Duration.ofSeconds(2),
                leaseTime = Duration.ofSeconds(10),
            )
        )
        val election = ExposedJdbcLeaderElection(db, options)

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
    fun `runIfLeader - recordHistory=true 시 action 실패 후 FAILED 이력이 기록된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderElectionOptions(
            recordHistory = true,
            leaderOptions = LeaderElectionOptions(
                waitTime = Duration.ofSeconds(2),
                leaseTime = Duration.ofSeconds(10),
            )
        )
        val election = ExposedJdbcLeaderElection(db, options)

        runCatching { election.runIfLeader(lockName) { throw RuntimeException("fail") } }

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
                waitTime = Duration.ofSeconds(5),
                leaseTime = Duration.ofSeconds(10),
            )
        )
        val election = ExposedJdbcLeaderElection(db, options)
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
        val election = ExposedJdbcLeaderElection(db)

        val result = election.runAsyncIfLeader(randomName(), VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action이 CF 반환 전 throw하면 CompletionException으로 전파되고 락이 해제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElection(db)

        assertThrows<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 정상 완료 후 락 행이 삭제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElection(db)

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

        val result = db.runVirtualIfLeader(randomName()) { "virtual ext 성공" }
            .get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "virtual ext 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `VirtualThread 선출 - runAsyncIfLeader 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderElection(db)
        val vtElection = ExposedJdbcVirtualThreadLeaderElection(election)

        val result = vtElection.runAsyncIfLeader(randomName()) { "vt 성공" }
            .get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "vt 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `VirtualThread 선출 - 락 보유 중 실패 시 null을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val holderLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        holderLock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30))

        try {
            val shortOptions = ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ofMillis(100),
                    leaseTime = Duration.ofSeconds(5),
                )
            )
            val election = ExposedJdbcLeaderElection(db, shortOptions)
            val vtElection = ExposedJdbcVirtualThreadLeaderElection(election)
            val result = vtElection.runAsyncIfLeader(lockName) { "실행하면 안 됨" }
                .get(5, TimeUnit.SECONDS)

            result.shouldBeNull()
        } finally {
            holderLock.unlock()
        }
    }
}
