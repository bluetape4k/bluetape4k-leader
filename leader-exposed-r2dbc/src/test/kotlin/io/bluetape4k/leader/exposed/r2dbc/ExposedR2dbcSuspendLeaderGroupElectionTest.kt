package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLock
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeInRange
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class ExposedR2dbcSuspendLeaderGroupElectionTest : AbstractExposedR2dbcLeaderTest() {

    companion object : KLoggingChannel()

    private val maxLeaders = 3

    private suspend fun makeGroupElection(testDB: TestR2dbcDB): ExposedR2dbcSuspendLeaderGroupElection {
        val db = setupDb(testDB)
        return ExposedR2dbcSuspendLeaderGroupElection(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(
                    maxLeaders = maxLeaders,
                    waitTime = Duration.ofSeconds(3),
                    leaseTime = Duration.ofSeconds(10),
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
    fun `runIfLeader - maxLeaders개 슬롯이 동시에 점유된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val executed = AtomicInteger(0)

        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = Duration.ofSeconds(5),
                leaseTime = Duration.ofSeconds(10),
            ),
        )

        val jobs = (1..maxLeaders).map {
            async {
                val election = ExposedR2dbcSuspendLeaderGroupElection(db, options)
                election.runIfLeader(lockName) {
                    executed.incrementAndGet()
                }
            }
        }
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
                lock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(30)) shouldBe true
            }
        }

        val contenderOptions = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = Duration.ofMillis(200),
                leaseTime = Duration.ofSeconds(10),
            ),
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
        )
        val contender = ExposedR2dbcSuspendLeaderGroupElection(db, contenderOptions)
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
    fun `runIfLeader - 잘못된 lockName은 IllegalArgumentException이 발생한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = makeGroupElection(testDB)

        assertThrows<IllegalArgumentException> {
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
                waitTime = Duration.ofSeconds(5),
                leaseTime = Duration.ofSeconds(30),
            ),
        )
        val election = ExposedR2dbcSuspendLeaderGroupElection(db, options)

        val holdJob = async {
            election.runIfLeader(lockName) {
                delay(500.milliseconds)
                "held"
            }
        }
        delay(100)

        val count = election.activeCountSuspend(lockName)
        log.debug { "활성 슬롯 수: $count" }
        count shouldBeGreaterOrEqualTo 1

        holdJob.await()
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

        val options = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = Duration.ofMillis(500),
                leaseTime = Duration.ofSeconds(5),
            ),
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
        )

        val jobs = (1..(maxLeaders * 2)).map {
            async {
                val election = ExposedR2dbcSuspendLeaderGroupElection(db, options)
                election.runIfLeader(lockName) {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(60.milliseconds)
                    concurrent.decrementAndGet()
                    executed.incrementAndGet()
                }
            }
        }
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
        val election = ExposedR2dbcSuspendLeaderGroupElection(db, options)

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
}
