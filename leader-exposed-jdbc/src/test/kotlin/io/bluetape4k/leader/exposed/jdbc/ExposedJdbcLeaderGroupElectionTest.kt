package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.tables.HistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeLessOrEqualTo
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class ExposedJdbcLeaderGroupElectionTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    private fun makeOptions(maxLeaders: Int = 3, waitSec: Long = 10, leaseSec: Long = 30) =
        ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = Duration.ofSeconds(waitSec),
                leaseTime = Duration.ofSeconds(leaseSec),
            )
        )

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions())

        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - blank lockName은 IllegalArgumentException을 발생시킨다`(testDB: TestDB) {
        val db = connectDb(testDB)
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions())

        assertThrows<IllegalArgumentException> {
            election.runIfLeader("   ") { }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 서로 다른 lockName은 독립적인 슬롯 풀을 가진다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions())

        val result1 = election.runIfLeader(randomName()) { "a" }
        val result2 = election.runIfLeader(randomName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - maxLeaders 슬롯이 모두 사용 중이면 짧은 waitTime으로 null을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val shortOptions = ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = Duration.ofMillis(200),
                leaseTime = Duration.ofSeconds(10),
            )
        )
        val singleElection = ExposedJdbcLeaderGroupElection(db, shortOptions)
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            singleElection.runIfLeader(lockName) {
                acquiredLatch.countDown()
                holdLatch.await()
            }
        }

        try {
            acquiredLatch.await(5, TimeUnit.SECONDS)
            val result = singleElection.runIfLeader(lockName) { "실행하면 안 됨" }
            result.shouldBeNull()
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 동시 실행 중인 리더 수가 maxLeaders를 초과하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val maxLeaders = 3
        val options = makeOptions(maxLeaders = maxLeaders, waitSec = 15, leaseSec = 30)
        val election = ExposedJdbcLeaderGroupElection(db, options)
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(maxLeaders * 3)
            .rounds(2)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    Thread.sleep(20)
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=$maxLeaders" }
        peakConcurrent.get() shouldBeLessOrEqualTo maxLeaders
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 예외 발생 후 슬롯이 반환되어 다음 호출이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions())

        runCatching { election.runIfLeader(lockName) { throw RuntimeException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `state - 초기 상태는 activeCount=0, isEmpty=true, isFull=false이다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val maxLeaders = 3
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions(maxLeaders = maxLeaders))
        val lockName = randomName()

        val state = election.state(lockName)

        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
        election.availableSlots(lockName) shouldBeEqualTo maxLeaders
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `state - 슬롯 획득 중 activeCount가 증가하고 해제 후 0으로 돌아온다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val maxLeaders = 3
        val options = makeOptions(maxLeaders = maxLeaders, waitSec = 10, leaseSec = 30)
        val election = ExposedJdbcLeaderGroupElection(db, options)
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(maxLeaders)

        repeat(maxLeaders) {
            executor.submit {
                election.runIfLeader(lockName) {
                    acquiredLatch.countDown()
                    holdLatch.await()
                }
            }
        }

        try {
            acquiredLatch.await(15, TimeUnit.SECONDS)

            val stateWhileHeld = election.state(lockName)
            stateWhileHeld.activeCount shouldBeEqualTo maxLeaders
            stateWhileHeld.isFull.shouldBeTrue()
            election.availableSlots(lockName) shouldBeEqualTo 0
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        val stateAfter = election.state(lockName)
        stateAfter.activeCount shouldBeEqualTo 0
        stateAfter.isEmpty.shouldBeTrue()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `activeCount - 만료된 슬롯 행은 집계에서 제외된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = makeOptions()
        val election = ExposedJdbcLeaderGroupElection(db, options)

        val expiredLock = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val leaseDuration = Duration.ofMillis(100)
        expiredLock.tryLock(Duration.ofSeconds(1), leaseDuration)
        // Wait 2x lease duration to ensure the acquired slot is expired before counting.
        val waitForExpirationMs = leaseDuration.toMillis() * 2
        Thread.sleep(waitForExpirationMs)

        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - recordHistory=true 시 ACQUIRED+COMPLETED 이력이 기록된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
            recordHistory = true,
        )
        val election = ExposedJdbcLeaderGroupElection(db, options)

        election.runIfLeader(lockName) { "done" }

        val rows = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .toList()
        }
        rows.size shouldBeEqualTo 1
        rows[0][LeaderLockHistoryTable.status] shouldBeEqualTo HistoryStatus.COMPLETED.name
        rows[0][LeaderLockHistoryTable.slot].shouldNotBeNull()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions())

        val result = election.runAsyncIfLeader(randomName(), VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action 동기 throw 후 슬롯이 반환되어 다음 호출이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions())

        assertThrows<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            futureOf { "복구 성공" }
        }.get(5, TimeUnit.SECONDS)
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action이 failedFuture 반환 시 슬롯이 반환되어 다음 호출 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderGroupElection(db, makeOptions(maxLeaders = 1))

        assertThrows<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("async 실패"))
            }.join()
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - maxLeaders=1 일 때 단일 리더 시맨틱과 동일하게 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val options = makeOptions(maxLeaders = 1)
        val election = ExposedJdbcLeaderGroupElection(db, options)

        // 첫 획득 → 정상
        val result = election.runIfLeader(lockName) { "ok" }
        result shouldBeEqualTo "ok"

        // 보유자가 잡고 있는 동안 다른 획득 시도 → null
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            election.runIfLeader(lockName) {
                acquiredLatch.countDown()
                holdLatch.await()
            }
        }
        try {
            acquiredLatch.await(5, TimeUnit.SECONDS)
            val shortElection = ExposedJdbcLeaderGroupElection(
                db,
                ExposedJdbcLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = Duration.ofMillis(100),
                        leaseTime = Duration.ofSeconds(5),
                    ),
                ),
            )
            shortElection.runIfLeader(lockName) { "should-not-run" }.shouldBeNull()
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `Database 확장함수 runIfLeaderGroup - 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val result = db.runIfLeaderGroup(randomName()) { "group ext 성공" }

        result shouldBeEqualTo "group ext 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `Database 확장함수 runAsyncIfLeaderGroup - 정상 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val result = db.runAsyncIfLeaderGroup(randomName()) {
            futureOf { "async group ext 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async group ext 성공"
    }
}
