package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.join
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.jdbc.history.ExposedLeaderHistorySink
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcGroupLock
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializer
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CancellationException as FutureCancellationException

class ExposedJdbcLeaderGroupElectionTest: AbstractExposedJdbcLeaderTest() {

    companion object: KLogging()

    private fun makeOptions(
        maxLeaders: Int = 3,
        waitSec: Long = 10,
        leaseSec: Long = 30,
        useDbTime: Boolean = false,
    ) =
        ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = waitSec.seconds,
                leaseTime = leaseSec.seconds,
                useDbTime = useDbTime,
            )
        )

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val election = ExposedJdbcLeaderGroupElector(db, makeOptions())

        val result = election.runIfLeader(randomName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - blank lockName은 IllegalArgumentException을 발생시킨다`(testDB: TestDB) {
        val db = connectDb(testDB)

        val election = ExposedJdbcLeaderGroupElector(db, makeOptions())

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("   ") { }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - 서로 다른 lockName은 독립적인 슬롯 풀을 가진다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val election = ExposedJdbcLeaderGroupElector(db, makeOptions())

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
                waitTime = 200.milliseconds,
                leaseTime = 10.seconds,
            )
        )
        val singleElection = ExposedJdbcLeaderGroupElector(db, shortOptions)
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
            acquiredLatch.await(5.seconds)
            val result = singleElection.runIfLeader(lockName) { "실행하면 안 됨" }
            result.shouldBeNull()
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5.seconds)
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - DB server time 모드에서 동시 리더 수가 maxLeaders를 초과하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val maxLeaders = 3
        val options = makeOptions(maxLeaders = maxLeaders, waitSec = 15, leaseSec = 30, useDbTime = true)
        val election = ExposedJdbcLeaderGroupElector(db, options)
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

                    Thread.sleep(Random.nextLong(10, 30))
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
        val election = ExposedJdbcLeaderGroupElector(db, makeOptions())

        runCatching {
            election.runIfLeader(lockName) {
                throw LeaderElectionException("실패")
            }
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `state - 초기 상태는 activeCount=0, isEmpty=true, isFull=false이다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val maxLeaders = 3
        val election = ExposedJdbcLeaderGroupElector(db, makeOptions(maxLeaders = maxLeaders))
        val lockName = randomName()

        val state = election.state(lockName)

        log.debug { "state=$state" }
        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()

        // 모든 slot 이 비었다
        election.availableSlots(lockName) shouldBeEqualTo maxLeaders
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `state - 슬롯 획득 중 activeCount가 증가하고 해제 후 0으로 돌아온다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val maxLeaders = 3
        val options = makeOptions(maxLeaders = maxLeaders, waitSec = 10, leaseSec = 30)
        val election = ExposedJdbcLeaderGroupElector(db, options)
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
            acquiredLatch.await(15.seconds)

            val stateWhileHeld = election.state(lockName)

            log.debug { "stateWhileHeld=$stateWhileHeld" }
            stateWhileHeld.activeCount shouldBeEqualTo maxLeaders
            stateWhileHeld.isFull.shouldBeTrue()

            election.availableSlots(lockName) shouldBeEqualTo 0
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5.seconds)
        }

        val stateAfter = election.state(lockName)
        log.debug { "stateAfter=$stateAfter" }
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
        val election = ExposedJdbcLeaderGroupElector(db, options)

        val expiredLock = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val leaseDuration = 100.milliseconds
        expiredLock.tryLock(1.seconds, leaseDuration)

        // Wait 2x lease duration to ensure the acquired slot is expired before counting.
        val waitForExpirationMs = leaseDuration.inWholeMilliseconds * 2
        Thread.sleep(waitForExpirationMs)

        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `activeCount - DB 시간 조회 실패 시 fail-closed로 maxLeaders를 반환하고 복구한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val maxLeaders = 2
        val options = ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = maxLeaders, useDbTime = true),
        )
        val election = ExposedJdbcLeaderGroupElector(db, options)
        val lockName = randomName()

        try {
            transaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }

            election.activeCount(lockName) shouldBeEqualTo maxLeaders
            election.availableSlots(lockName) shouldBeEqualTo 0
            election.state(lockName).activeCount shouldBeEqualTo maxLeaders
        } finally {
            ExposedJdbcSchemaInitializer.resetFor(db)
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            cleanTables(db)
        }

        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - historyRecorder 제공 시 ACQUIRED+COMPLETED 이력이 기록된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val options = ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
        )
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderGroupElector(db, options, recorder)

        election.runIfLeader(lockName) { "done" } shouldBeEqualTo "done"

        val rows = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .toList()
        }
        log.debug { "rows=$rows" }
        rows.size shouldBeEqualTo 1
        rows[0][LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.COMPLETED
        rows[0][LeaderLockHistoryTable.slot].shouldNotBeNull()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - action 취소 후 FAILED 이력을 기록하고 슬롯을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val options = ExposedJdbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
        )
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderGroupElector(db, options, recorder)
        val cancellation = CancellationException("cancel group action")

        val thrown = assertFailsWith<CancellationException> {
            election.runIfLeader(lockName) { throw cancellation }
        }

        thrown shouldBeEqualTo cancellation
        val history = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .single()
        }
        log.debug { "history=$history" }
        history[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED
        history[LeaderLockHistoryTable.finishedAt].shouldNotBeNull()
        history[LeaderLockHistoryTable.durationMs].shouldNotBeNull() shouldBeGreaterOrEqualTo 0L

        election.activeCount(lockName) shouldBeEqualTo 0

        election.runIfLeader(lockName) { "group-recovered-after-cancel" } shouldBeEqualTo "group-recovered-after-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action을 실행하고 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val election = ExposedJdbcLeaderGroupElector(db, makeOptions())

        val result = election.runAsyncIfLeader(randomName(), VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5.seconds)

        result shouldBeEqualTo "async 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - terminal cleanup 완료 후 결과를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val cleanupStarted = CountDownLatch(1)
        val cleanupAllowed = CountDownLatch(1)
        val recorder = object: SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db)) {
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
                cleanupStarted.countDown()
                check(cleanupAllowed.await(5.seconds)) { "terminal cleanup 대기 시간이 초과되었습니다." }
                super.recordCompleted(key, finishedAt, durationMs)
            }
        }
        val election = ExposedJdbcLeaderGroupElector(db, makeOptions(maxLeaders = 1), recorder)
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()
        val completionExecutor = Executors.newSingleThreadExecutor()

        try {
            val resultFuture = election.runAsyncIfLeader(randomName(), VirtualThreadExecutor) {
                actionStarted.countDown()
                actionFuture
            }

            actionStarted.await(5.seconds).shouldBeTrue()
            completionExecutor.submit { actionFuture.complete("async cleanup 완료") }

            cleanupStarted.await(5.seconds).shouldBeTrue()
            resultFuture.isDone.shouldBeFalse()

            cleanupAllowed.countDown()
            resultFuture.get(5.seconds) shouldBeEqualTo "async cleanup 완료"
        } finally {
            cleanupAllowed.countDown()
            completionExecutor.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action 동기 throw 후 슬롯이 반환되어 다음 호출이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val election = ExposedJdbcLeaderGroupElector(db, makeOptions())

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            completableFutureOf("복구 성공")
        }.get(5.seconds)
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action이 failedFuture 반환 시 슬롯이 반환되어 다음 호출 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderGroupElector(db, makeOptions(maxLeaders = 1))

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                failedCompletableFutureOf(IllegalStateException("async 실패"))
            }.join()
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 두 번째 executor 제출 거부 후 획득한 슬롯을 정리한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val election = ExposedJdbcLeaderGroupElector(db, makeOptions(maxLeaders = 1, waitSec = 1))
        val worker = Executors.newSingleThreadExecutor()
        val submissions = AtomicInteger()
        val actionInvoked = AtomicBoolean()

        val executor = Executor { command ->
            if (submissions.incrementAndGet() == 1) {
                worker.execute(command)
            } else {
                throw RejectedExecutionException("second submission rejected")
            }
        }

        try {
            val resultFuture = runCatching {
                election.runAsyncIfLeader(lockName, executor) {
                    actionInvoked.set(true)
                    completableFutureOf("실행되면 안 됨")
                }
            }.getOrElse { failedCompletableFutureOf(it) }

            val failure = assertFailsWith<CompletionException> {
                resultFuture.join()
            }
            failure.cause.shouldBeInstanceOf<RejectedExecutionException>()

            actionInvoked.get().shouldBeFalse()

            election.runIfLeader(lockName) { "executor 거부 후 슬롯 복구" } shouldBeEqualTo "executor 거부 후 슬롯 복구"
        } finally {
            worker.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action future 취소 후 FAILED 이력을 기록하고 슬롯을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderGroupElector(
            db,
            makeOptions(maxLeaders = 1),
            recorder,
        )
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            actionStarted.countDown()
            actionFuture
        }

        actionStarted.await(5.seconds).shouldBeTrue()
        actionFuture.cancel(false).shouldBeTrue()

        val thrown = assertFailsWith<CompletionException> {
            resultFuture.join()
        }
        thrown.cause.shouldBeInstanceOf<FutureCancellationException>()

        val history = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .single()
        }
        history[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED
        history[LeaderLockHistoryTable.finishedAt].shouldNotBeNull()
        history[LeaderLockHistoryTable.durationMs].shouldNotBeNull() shouldBeGreaterOrEqualTo 0L

        election.runIfLeader(lockName) { "async-group-recovered-after-cancel" } shouldBeEqualTo "async-group-recovered-after-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - recordAcquired 인터럽트 후 슬롯을 정리한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val recorder = object: SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db)) {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
                throw InterruptedException("group acquire history interrupted")
            }
        }
        val options = makeOptions(maxLeaders = 1, waitSec = 1, leaseSec = 30)
        val election = ExposedJdbcLeaderGroupElector(db, options, recorder)
        val actionInvocations = AtomicInteger()

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            actionInvocations.incrementAndGet()
            completableFutureOf("실행되면 안 됨")
        }

        val failure = assertFailsWith<CompletionException> {
            resultFuture.join()
        }
        failure.cause.shouldBeInstanceOf<InterruptedException>()
        actionInvocations.get() shouldBeEqualTo 0

        ExposedJdbcLeaderGroupElector(db, options).runIfLeader(lockName) { "복구 성공" } shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 반환 future 취소를 action에 전파하고 FAILED 이력과 슬롯 반환을 보장한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderGroupElector(
            db,
            makeOptions(maxLeaders = 1),
            recorder,
        )
        val actionStarted = CountDownLatch(1)
        val actionFuture = java.util.concurrent.CompletableFuture<String>()

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            actionStarted.countDown()
            actionFuture
        }

        actionStarted.await(5.seconds).shouldBeTrue()
        resultFuture.cancel(false).shouldBeTrue()

        assertFailsWith<FutureCancellationException> {
            resultFuture.join()
        }

        await atMost 5.seconds withPollInterval 100.milliseconds until {
            actionFuture.isCancelled
        }

        await atMost 5.seconds withPollInterval 100.milliseconds untilAsserted {
            val history = transaction(db) {
                LeaderLockHistoryTable.selectAll()
                    .where { LeaderLockHistoryTable.lockName eq lockName }
                    .single()
            }
            history[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED
            history[LeaderLockHistoryTable.finishedAt].shouldNotBeNull()
            history[LeaderLockHistoryTable.durationMs].shouldNotBeNull() shouldBeGreaterOrEqualTo 0L
        }

        election.runIfLeader(lockName) { "async-group-recovered-after-result-cancel" } shouldBeEqualTo
                "async-group-recovered-after-result-cancel"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeaderResult - 반환 future 취소를 action에 전파하고 FAILED 이력과 슬롯 반환을 보장한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderGroupElector(
            db,
            makeOptions(maxLeaders = 1),
            recorder,
        )
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        val resultFuture = election.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "group-result-cancel-node"),
            VirtualThreadExecutor,
        ) {
            actionStarted.countDown()
            actionFuture
        }

        actionStarted.await(5.seconds).shouldBeTrue()
        resultFuture.cancel(false).shouldBeTrue()

        assertFailsWith<FutureCancellationException> {
            resultFuture.join()
        }

        await atMost 5.seconds withPollInterval 100.milliseconds until {
            actionFuture.isCancelled
        }

        await atMost 5.seconds withPollInterval 100.milliseconds until {
            val historyStatus = transaction(db) {
                LeaderLockHistoryTable.selectAll()
                    .where { LeaderLockHistoryTable.lockName eq lockName }
                    .single()[LeaderLockHistoryTable.status]
            }
            historyStatus == LeaderHistoryStatus.FAILED
        }

        election.runIfLeader(lockName) { "group result 취소 후 복구" } shouldBeEqualTo "group result 취소 후 복구"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 반환 future 취소 시 슬롯 획득 대기를 중단한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(10L))
        holder.tryLock(Duration.ZERO, 30.seconds).shouldBeTrue()

        val election = ExposedJdbcLeaderGroupElector(db, makeOptions(maxLeaders = 1, waitSec = 10))
        val executor = Executors.newSingleThreadExecutor()
        val actionInvocations = AtomicInteger()

        try {
            val resultFuture = election.runAsyncIfLeader(lockName, executor) {
                actionInvocations.incrementAndGet()
                completableFutureOf("실행되면 안 됨")
            }

            resultFuture.cancel(false).shouldBeTrue()

            assertFailsWith<FutureCancellationException> {
                resultFuture.join(3.seconds)
            }
            executor.submit { }.get(3.seconds)
            actionInvocations.get() shouldBeEqualTo 0
        } finally {
            executor.shutdownNow()
            holder.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - maxLeaders=1 일 때 단일 리더 시맨틱과 동일하게 동작한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val lockName = randomName()
        val options = makeOptions(maxLeaders = 1)
        val election = ExposedJdbcLeaderGroupElector(db, options)

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
            acquiredLatch.await(5.seconds).shouldBeTrue()

            val shortElection = ExposedJdbcLeaderGroupElector(
                db,
                ExposedJdbcLeaderGroupElectionOptions(
                    leaderGroupOptions = LeaderGroupElectionOptions(
                        maxLeaders = 1,
                        waitTime = 100.milliseconds,
                        leaseTime = 5.seconds,
                    ),
                ),
            )
            shortElection.runIfLeader(lockName) { "should-not-run" }.shouldBeNull()
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5.seconds).shouldBeTrue()
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
            completableFutureOf("async group ext 성공")
        }.get(5.seconds)

        result shouldBeEqualTo "async group ext 성공"
    }
}
