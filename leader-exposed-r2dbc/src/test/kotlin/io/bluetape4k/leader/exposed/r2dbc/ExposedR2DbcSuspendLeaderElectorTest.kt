package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class ExposedR2DbcSuspendLeaderElectorTest: AbstractExposedR2dbcLeaderTest() {

    companion object: KLoggingChannel()

    private suspend fun makeElection(testDB: TestR2dbcDB): ExposedR2DbcSuspendLeaderElector {
        val db = setupDb(testDB)
        return ExposedR2DbcSuspendLeaderElector(
            db,
            ExposedR2dbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 2.seconds,
                    leaseTime = 10.seconds,
                ),
                retryStrategy = RetryStrategy.Jitter(),
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 리더 선출 성공 시 action 결과를 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = makeElection(testDB)

        val result = election.runIfLeader(randomName()) { "done" }

        result shouldBeEqualTo "done"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 동일 lockName에 이미 리더가 있으면 null을 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val leaderOptions = ExposedR2dbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 30.seconds,
                leaseTime = 30.seconds,
            ),
        )
        val holder = ExposedR2DbcSuspendLeaderElector(db, leaderOptions)

        val holderJob = async {
            holder.runIfLeader(lockName) {
                delay(500.milliseconds)
                "leader"
            }
        }
        delay(100.milliseconds)

        val contenderOptions = ExposedR2dbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 30.seconds,
            ),
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
        )
        val contender = ExposedR2DbcSuspendLeaderElector(db, contenderOptions)
        val result = contender.runIfLeader(lockName) { "contender" }

        result.shouldBeNull()
        holderJob.await() shouldBeEqualTo "leader"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 예외 후 재선출이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = makeElection(testDB)

        runCatching {
            election.runIfLeader(lockName) { throw LeaderElectionException("오류 발생") }
        }

        val result = election.runIfLeader(lockName) { "recovered" }
        result shouldBeEqualTo "recovered"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 성공 후 순차 재실행이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = makeElection(testDB)

        val r1 = election.runIfLeader(lockName) { "first" }
        val r2 = election.runIfLeader(lockName) { "second" }

        r1 shouldBeEqualTo "first"
        r2 shouldBeEqualTo "second"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 잘못된 lockName은 IllegalArgumentException이 발생한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = makeElection(testDB)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("has space") { "never" }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - recordHistory=true 시 이력 기록 후 정상 반환된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val options = ExposedR2dbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            ),
            recordHistory = true,
            lockOwner = "test-worker",
        )
        val election = ExposedR2DbcSuspendLeaderElector(db, options)

        val result = election.runIfLeader(randomName()) { "with-history" }

        result shouldBeEqualTo "with-history"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `코루틴 10개 경합 시 상호 배제 — 단 하나만 리더로 선출된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)

        val jobs = (1..10).map {
            async {
                val options = ExposedR2dbcLeaderElectionOptions(
                    leaderOptions = LeaderElectionOptions(
                        waitTime = 300.milliseconds,
                        leaseTime = 5.seconds,
                    ),
                    retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
                )
                val election = ExposedR2DbcSuspendLeaderElector(db, options)
                election.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(50.milliseconds)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        log.debug { "최대 동시 실행: ${maxConcurrent.get()}, 총 실행 횟수: ${executed.get()}" }
        maxConcurrent.get() shouldBeEqualTo 1
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    // ─── suspendRunIfLeader 확장 함수 ──────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `suspendRunIfLeader 확장 함수로 리더 선출이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)

        val result = db.suspendRunIfLeader(randomName()) { "ext-result" }

        result shouldBeEqualTo "ext-result"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `suspendRunIfLeader 확장 함수 - null이 아닌 결과를 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)

        val result = db.suspendRunIfLeader(randomName()) { 42 }

        result.shouldNotBeNull()
        result shouldBeEqualTo 42
    }

    // ─── Tier 6: 누락 테스트 보완 ────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - CancellationException 발생 시 락이 해제되어 재획득 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedR2dbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 30.seconds,
            ),
        )

        // withTimeout으로 CancellationException 강제 발생 → NonCancellable finally에서 락 해제
        runCatching {
            withTimeout(200.milliseconds) {
                ExposedR2DbcSuspendLeaderElector(db, options).runIfLeader(lockName) {
                    delay(10_000.milliseconds)
                }
            }
        }

        val result = ExposedR2DbcSuspendLeaderElector(db, options).runIfLeader(lockName) { "reacquired" }
        result shouldBeEqualTo "reacquired"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `recordHistory=true 이고 action이 예외를 던지면 FAILED 상태로 기록된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedR2dbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 2.seconds,
                leaseTime = 10.seconds,
            ),
            recordHistory = true,
            lockOwner = "test-worker",
        )
        val election = ExposedR2DbcSuspendLeaderElector(db, options)

        runCatching { election.runIfLeader(lockName) { throw LeaderElectionException("의도적 실패") } }

        val failedCount = suspendTransaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where {
                    (LeaderLockHistoryTable.lockName eq lockName) and
                            (LeaderLockHistoryTable.status eq "FAILED")
                }
                .count()
        }
        failedCount shouldBeGreaterOrEqualTo 1L
    }
}
