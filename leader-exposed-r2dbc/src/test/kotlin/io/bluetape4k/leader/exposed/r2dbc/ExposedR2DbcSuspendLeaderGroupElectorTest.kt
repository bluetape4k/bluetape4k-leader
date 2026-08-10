package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcSchemaInitializer
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.LOCK_HISTORY_TABLE_NAME
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.statements.GlobalSuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInRange
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger

class ExposedR2DbcSuspendLeaderGroupElectorTest: AbstractExposedR2dbcLeaderTest() {

    companion object: KLoggingChannel()

    private val maxLeaders = 3

    private suspend fun makeGroupElection(
        testDB: TestR2dbcDB,
        useDbTime: Boolean = false,
    ): ExposedR2DbcSuspendLeaderGroupElector {
        val db = setupDb(testDB)
        return ExposedR2DbcSuspendLeaderGroupElector(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = maxLeaders,
                    waitTime = 3.seconds,
                    leaseTime = 10.seconds,
                    useDbTime = useDbTime,
                ),
                retryStrategy = RetryStrategy.Jitter(),
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 슬롯이 비어 있으면 action 결과를 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = makeGroupElection(testDB)

        val result = election.runIfLeader(randomName()) { "group-done" }

        result shouldBeEqualTo "group-done"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - DB server time 모드에서 maxLeaders개 슬롯이 동시에 점유된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val executed = AtomicInteger(0)
        val allLeadersAcquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = 5.seconds,
                leaseTime = 10.seconds,
                useDbTime = true,
            ),
        )

        val jobs = (1..maxLeaders).map {
            async {
                val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)
                election.runIfLeader(lockName) {
                    if (executed.incrementAndGet() == maxLeaders) {
                        allLeadersAcquired.complete(Unit)
                    }
                    release.await()
                }
            }
        }
        withTimeout(5.seconds) { allLeadersAcquired.await() }
        release.complete(Unit)
        jobs.awaitAll()

        executed.get() shouldBeEqualTo maxLeaders
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 모든 슬롯이 점유 중이면 null을 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        // ExposedR2dbcGroupLock으로 모든 슬롯을 직접 선점 (타이밍 의존성 제거)
        val locks = (0 until maxLeaders).map { slot ->
            ExposedR2dbcGroupLock(db, lockName, slot, RetryStrategy.Jitter()).also { lock ->
                lock.tryLock(2.seconds, 30.seconds).shouldBeTrue()
            }
        }

        val contenderOptions = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = 200.milliseconds,
                leaseTime = 10.seconds,
            ),
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
        )
        val contender = ExposedR2DbcSuspendLeaderGroupElector(db, contenderOptions)
        val result = contender.runIfLeader(lockName) { "contender" }

        result.shouldBeNull()
        locks.forEach { it.unlock() }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 예외 후 재선출이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = makeGroupElection(testDB)

        runCatching {
            election.runIfLeader(lockName) { throw LeaderGroupElectionException("그룹 오류") }
        }

        val result = election.runIfLeader(lockName) { "group-recovered" }
        result shouldBeEqualTo "group-recovered"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 취소 후 슬롯과 캐시가 정리되어 재선출이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = makeGroupElection(testDB)

        assertFailsWith<CancellationException> {
            election.runIfLeader(lockName) { throw CancellationException("cancel group action") }
        }

        election.activeCount(lockName) shouldBeEqualTo 0
        election.runIfLeader(lockName) { "group-recovered-after-cancel" } shouldBeEqualTo "group-recovered-after-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `실제 Job 취소에서도 슬롯과 캐시가 정리되어 재선출이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = makeGroupElection(testDB)
        val started = CompletableDeferred<Unit>()

        val job = async {
            election.runIfLeader(lockName) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()

        election.activeCount(lockName) shouldBeEqualTo 0
        election.runIfLeader(lockName) { "group-recovered-after-job-cancel" } shouldBeEqualTo "group-recovered-after-job-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `recordAcquired 설정 중 취소되어도 슬롯과 캐시가 정리된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = 2,
                waitTime = 1.seconds,
                leaseTime = 10.seconds,
                useDbTime = true,
            ),
            recordHistory = true,
        )
        val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)
        val interceptor = CancelOnHistoryInsert()
        R2dbcTransaction.globalInterceptors += interceptor
        try {
            assertFailsWith<CancellationException> {
                election.runIfLeader(lockName) { "should-not-run" }
            }
        } finally {
            R2dbcTransaction.globalInterceptors.remove(interceptor)
        }

        election.activeCount(lockName) shouldBeEqualTo 0
        election.runIfLeader(lockName) { "recovered-after-setup-cancel" } shouldBeEqualTo "recovered-after-setup-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 잘못된 lockName은 IllegalArgumentException이 발생한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = makeGroupElection(testDB)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("has space") { "never" }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `state - lockName 상태 조회가 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = makeGroupElection(testDB)
        val lockName = randomName()

        val state = election.state(lockName)

        state.maxLeaders shouldBeEqualTo maxLeaders
        state.lockName shouldBeEqualTo lockName
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `activeCountSuspend - 선출 후 활성 슬롯 수가 증가한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = 5.seconds,
                leaseTime = 30.seconds,
            ),
        )
        val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holdJob = async {
            election.runIfLeader(lockName) {
                acquired.complete(Unit)
                release.await()
                "held"
            }
        }
        acquired.await()

        val count = election.activeCountSuspend(lockName)
        log.debug { "활성 슬롯 수: $count" }
        count shouldBeGreaterOrEqualTo 1

        release.complete(Unit)
        holdJob.await()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `activeCountSuspend - DB 시간 조회 실패 시 fail-closed로 maxLeaders를 반환하고 복구한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val maxLeaders = 2
        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = maxLeaders, useDbTime = true),
        )
        val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)
        val lockName = randomName()

        try {
            suspendTransaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }

            election.activeCountSuspend(lockName) shouldBeEqualTo maxLeaders
            election.activeCount(lockName) shouldBeEqualTo maxLeaders
            election.availableSlots(lockName) shouldBeEqualTo 0
            election.state(lockName).activeCount shouldBeEqualTo maxLeaders
        } finally {
            ExposedR2dbcSchemaInitializer.resetFor(db)
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            cleanTables(db)
        }

        election.activeCountSuspend(lockName) shouldBeEqualTo 0
        election.activeCount(lockName) shouldBeEqualTo 0

        election.runIfLeader(lockName) { "recovered-after-db-time" } shouldBeEqualTo "recovered-after-db-time"
        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `복구 후 정상 경합 결과가 fail-closed 상태를 해제한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = 200.milliseconds,
                leaseTime = 30.seconds,
                useDbTime = true,
            ),
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
        )
        val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)

        try {
            suspendTransaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }
            election.activeCountSuspend(lockName) shouldBeEqualTo maxLeaders
        } finally {
            ExposedR2dbcSchemaInitializer.resetFor(db)
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            cleanTables(db)
        }

        val holders = (0 until maxLeaders).map { slot ->
            ExposedR2dbcGroupLock(
                db,
                lockName,
                slot,
                RetryStrategy.Jitter(),
                useDbTime = true,
            ).also { it.tryLock(1.seconds, 30.seconds).shouldNotBeNull().shouldBeTrue() }
        }

        election.runIfLeader(lockName) { "must-not-run-while-contended" }.shouldBeNull()
        election.activeCount(lockName) shouldBeEqualTo 0
        holders.forEach { it.unlock() }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `서로 다른 lockName의 활성 상태는 격리된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockA = randomName()
        val lockB = randomName()
        val election = makeGroupElection(testDB)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holdJob = async {
            election.runIfLeader(lockA) {
                acquired.complete(Unit)
                release.await()
            }
        }
        acquired.await()

        try {
            election.activeCount(lockA) shouldBeEqualTo 1
            election.state(lockA).activeCount shouldBeEqualTo 1
            election.availableSlots(lockA) shouldBeEqualTo maxLeaders - 1

            election.activeCount(lockB) shouldBeEqualTo 0
            election.state(lockB).activeCount shouldBeEqualTo 0
            election.availableSlots(lockB) shouldBeEqualTo maxLeaders

            election.activeCountSuspend(lockB) shouldBeEqualTo 0
            election.activeCount(lockA) shouldBeEqualTo 1
            election.state(lockA).activeCount shouldBeEqualTo 1

            election.activeCountSuspend(lockA) shouldBeEqualTo 1
            election.activeCount(lockB) shouldBeEqualTo 0
            election.state(lockB).activeCount shouldBeEqualTo 0
        } finally {
            release.complete(Unit)
            holdJob.await()
        }

        election.activeCount(lockA) shouldBeEqualTo 0
        election.activeCount(lockB) shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `maxLeaders 초과 경합 — 동시 실행은 maxLeaders 이하로 제한된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val executed = AtomicInteger(0)
        val acquired = AtomicInteger(0)
        val allLeadersAcquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = 5.seconds,
                leaseTime = 5.seconds,
            ),
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
        )

        val jobs = (1..(maxLeaders * 2)).map {
            async {
                val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)
                election.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    if (acquired.incrementAndGet() == maxLeaders) {
                        allLeadersAcquired.complete(Unit)
                    }
                    release.await()
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
        }
        allLeadersAcquired.await()
        release.complete(Unit)
        jobs.awaitAll()

        log.debug { "최대 동시 실행: ${maxConcurrent.get()}, 총 실행 횟수: ${executed.get()}" }
        maxConcurrent.get() shouldBeInRange 1..maxLeaders
        executed.get() shouldBeGreaterOrEqualTo 1
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `recordHistory=true 시 이력 기록 후 정상 반환된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
            recordHistory = true,
            lockOwner = "group-worker",
        )
        val election = ExposedR2DbcSuspendLeaderGroupElector(db, options)

        val result = election.runIfLeader(randomName()) { "with-history" }

        result shouldBeEqualTo "with-history"
    }

    // ─── suspendRunIfLeaderGroup 확장 함수 ────────────────────────────────────

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `suspendRunIfLeaderGroup 확장 함수로 그룹 리더 선출이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)

        val result = db.suspendRunIfLeaderGroup(randomName()) { "group-ext-result" }

        result shouldBeEqualTo "group-ext-result"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `suspendRunIfLeaderGroup 확장 함수 - null이 아닌 결과를 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)

        val result = db.suspendRunIfLeaderGroup(randomName()) { 99 }

        result.shouldNotBeNull()
        result shouldBeEqualTo 99
    }

    private class CancelOnHistoryInsert : GlobalSuspendStatementInterceptor {
        private var cancelled = false

        override suspend fun beforeExecution(transaction: R2dbcTransaction, context: StatementContext) {
            if (!cancelled && context.sql(transaction).contains(LOCK_HISTORY_TABLE_NAME, ignoreCase = true)) {
                cancelled = true
                throw CancellationException("cancel during group history setup")
            }
        }
    }
}
