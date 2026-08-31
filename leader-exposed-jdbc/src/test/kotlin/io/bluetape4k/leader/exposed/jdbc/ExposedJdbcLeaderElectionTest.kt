package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.exposed.jdbc.history.ExposedLeaderHistorySink
import io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcLock
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException as FutureCancellationException
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
    fun `ExposedJdbcLock - useDbTime true면 DB 서버 시간 경로로 락을 획득한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val lock = ExposedJdbcLock(
            db = db,
            lockName = lockName,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )

        try {
            lock.tryLock(1.seconds, 5.seconds) shouldBeEqualTo true
            lock.isHeldByCurrentInstance() shouldBeEqualTo true

            val lockedUntil = transaction(db) {
                LeaderLockTable
                    .selectAll()
                    .where { LeaderLockTable.lockName eq lockName }
                    .singleOrNull()
                    ?.get(LeaderLockTable.lockedUntil)
            }
            lockedUntil.shouldNotBeNull()
        } finally {
            lock.unlock()
        }
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
    fun `runIfLeader - action 예외 발생 시 예외가 재전파되고 락 행이 삭제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(db)

        assertFailsWith<LeaderElectionException> {
            election.runIfLeader(lockName) {
                throw LeaderElectionException("테스트 예외")
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
                            (LeaderLockHistoryTable.status eq LeaderHistoryStatus.FAILED.name)
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
        rows[0][LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.COMPLETED.name
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runIfLeader - historyRecorder 사용 시 action 실패 후 예외가 재전파되고 FAILED 이력이 기록된다`(testDB: TestDB) {
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

        assertFailsWith<LeaderElectionException> {
            election.runIfLeader(lockName) { throw LeaderElectionException("fail") }
        }

        val rows = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .toList()
        }
        rows.size shouldBeEqualTo 1
        rows[0][LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED.name
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
    fun `runAsyncIfLeader - 반환 future 취소 시 락 획득 대기를 중단한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedJdbcLock(db, lockName, RetryStrategy.Fixed(10L))
        holder.tryLock(Duration.ZERO, 30.seconds).shouldBeTrue()
        val election = ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 10.seconds,
                    leaseTime = 30.seconds,
                ),
                retryStrategy = RetryStrategy.Fixed(10L),
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        val actionInvocations = AtomicInteger()

        try {
            val resultFuture = election.runAsyncIfLeader(lockName, executor) {
                actionInvocations.incrementAndGet()
                CompletableFuture.completedFuture("실행되면 안 됨")
            }

            resultFuture.cancel(false).shouldBeTrue()
            assertFailsWith<FutureCancellationException> { resultFuture.join() }
            executor.submit { }.get(3, TimeUnit.SECONDS)
            actionInvocations.get() shouldBeEqualTo 0
        } finally {
            executor.shutdownNow()
            holder.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 획득 후 composition callback 전에 취소되면 action 없이 락을 반납한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 2.seconds,
                    leaseTime = 30.seconds,
                ),
            ),
        )
        val worker = Executors.newSingleThreadExecutor()
        val taskNumber = AtomicInteger()
        val composeReady = CountDownLatch(1)
        val composeAllowed = CountDownLatch(1)
        val actionInvocations = AtomicInteger()
        val executor = Executor { command ->
            worker.execute {
                if (taskNumber.incrementAndGet() == 2) {
                    composeReady.countDown()
                    check(composeAllowed.await(5, TimeUnit.SECONDS)) { "composition callback 대기 시간이 초과되었습니다." }
                }
                command.run()
            }
        }

        try {
            val resultFuture = election.runAsyncIfLeader(lockName, executor) {
                actionInvocations.incrementAndGet()
                CompletableFuture.completedFuture("실행되면 안 됨")
            }

            composeReady.await(5, TimeUnit.SECONDS).shouldBeTrue()
            resultFuture.cancel(false).shouldBeTrue()
            composeAllowed.countDown()
            assertFailsWith<FutureCancellationException> { resultFuture.join() }
            worker.submit { }.get(3, TimeUnit.SECONDS)
            actionInvocations.get() shouldBeEqualTo 0

            election.runIfLeader(lockName) { "compose 경계 복구" } shouldBeEqualTo "compose 경계 복구"
        } finally {
            composeAllowed.countDown()
            worker.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - history 기록 중 취소되면 action 없이 FAILED 이력과 락 반납을 완료한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val recordStarted = CountDownLatch(1)
        val recordAllowed = CountDownLatch(1)
        val recorder = object : SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db)) {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
                recordStarted.countDown()
                check(recordAllowed.await(5, TimeUnit.SECONDS)) { "history 기록 대기 시간이 초과되었습니다." }
                return super.recordAcquired(record)
            }
        }
        val election = ExposedJdbcLeaderElector(db, historyRecorder = recorder)
        val actionInvocations = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val executor = Executor { command -> worker.execute(command) }

        try {
            val resultFuture = election.runAsyncIfLeader(lockName, executor) {
                actionInvocations.incrementAndGet()
                CompletableFuture.completedFuture("실행되면 안 됨")
            }

            recordStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            resultFuture.cancel(false).shouldBeTrue()
            recordAllowed.countDown()
            assertFailsWith<FutureCancellationException> { resultFuture.join() }
            worker.submit { }.get(3, TimeUnit.SECONDS)
            actionInvocations.get() shouldBeEqualTo 0

            val history = transaction(db) {
                LeaderLockHistoryTable.selectAll()
                    .where { LeaderLockHistoryTable.lockName eq lockName }
                    .single()
            }
            history[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED.name
            election.runIfLeader(lockName) { "history 경계 복구" } shouldBeEqualTo "history 경계 복구"
        } finally {
            recordAllowed.countDown()
            worker.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - recordAcquired 인터럽트 후 watchdog과 락을 정리한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val recorder = object : SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db)) {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? {
                throw InterruptedException("acquire history interrupted")
            }
        }
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 30.seconds,
                autoExtend = true,
            ),
        )
        val election = ExposedJdbcLeaderElector(db, options, recorder)

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            CompletableFuture.completedFuture("실행되면 안 됨")
        }

        val failure = assertFailsWith<CompletionException> { resultFuture.join() }
        failure.cause.shouldBeInstanceOf(InterruptedException::class)
        ExposedJdbcLeaderElector(db, options).runIfLeader(lockName) { "복구 성공" } shouldBeEqualTo "복구 성공"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - finishAction history 예외 후 원 결과와 락 정리를 보장한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val options = ExposedJdbcLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 30.seconds,
                autoExtend = true,
            ),
        )
        val actionFailure = IllegalStateException("action 실패")
        val recorder = object : SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db)) {
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) {
                throw InterruptedException("completed history interrupted")
            }

            override fun recordFailed(
                key: LeaderHistoryKey,
                finishedAt: Instant,
                durationMs: Long,
                error: Throwable?,
            ) {
                throw InterruptedException("failed history interrupted")
            }
        }
        val election = ExposedJdbcLeaderElector(db, options, recorder)

        val completedLockName = randomName()
        val completedFuture = election.runAsyncIfLeader(completedLockName, VirtualThreadExecutor) {
            CompletableFuture.completedFuture("완료")
        }
        val completedFailure = assertFailsWith<CompletionException> { completedFuture.join() }
        completedFailure.cause.shouldBeInstanceOf(InterruptedException::class)
        ExposedJdbcLeaderElector(db, options).runIfLeader(completedLockName) { "완료 복구" } shouldBeEqualTo "완료 복구"

        val failedLockName = randomName()
        val failedFuture = election.runAsyncIfLeader<Int>(failedLockName, VirtualThreadExecutor) {
            CompletableFuture.failedFuture(actionFailure)
        }

        val failedCompletion = assertFailsWith<CompletionException> { failedFuture.join() }
        failedCompletion.cause shouldBeEqualTo actionFailure
        ExposedJdbcLeaderElector(db, options).runIfLeader(failedLockName) { "실패 복구" } shouldBeEqualTo "실패 복구"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action future 취소 후 FAILED 이력을 기록하고 락을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderElector(db, historyRecorder = recorder)
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            actionStarted.countDown()
            actionFuture
        }

        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        actionFuture.cancel(false).shouldBeTrue()

        val thrown = assertFailsWith<CompletionException> { resultFuture.join() }
        thrown.cause.shouldNotBeNull()
        (thrown.cause is FutureCancellationException).shouldBeTrue()
        val history = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .single()
        }
        history[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED.name
        election.runIfLeader(lockName) { "action 취소 후 복구" } shouldBeEqualTo "action 취소 후 복구"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - 반환 future 취소를 action에 전파하고 FAILED 이력과 락 반환을 보장한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderElector(db, historyRecorder = recorder)
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            actionStarted.countDown()
            actionFuture
        }

        actionStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
        resultFuture.cancel(false).shouldBeTrue()
        assertFailsWith<FutureCancellationException> { resultFuture.join() }
        actionFuture.isCancelled.shouldBeTrue()

        val history = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq lockName }
                .single()
        }
        history[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED.name
        election.runIfLeader(lockName) { "반환 취소 후 복구" } shouldBeEqualTo "반환 취소 후 복구"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - action이 CF 반환 전 throw하면 예외를 전파하고 락이 해제된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val recorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db))
        val election = ExposedJdbcLeaderElector(db, historyRecorder = recorder)

        val failure = assertFailsWith<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }
        failure.cause shouldBeInstanceOf IllegalStateException::class

        // 락이 해제되어 다음 호출이 성공해야 함
        val next = election.runIfLeader(lockName) { "복구 성공" }
        next shouldBeEqualTo "복구 성공"

        val failedCount = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where {
                    (LeaderLockHistoryTable.lockName eq lockName) and
                            (LeaderLockHistoryTable.status eq LeaderHistoryStatus.FAILED.name)
                }
                .count()
        }
        failedCount shouldBeEqualTo 1L
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeaderResult - action 동기 throw를 ActionFailed로 분류하고 락을 해제한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(db)
        val failure = IllegalStateException("action 동기 예외")

        val result = election.runAsyncIfLeaderResult<Int>(LeaderSlot(lockName, "node-755"), VirtualThreadExecutor) {
            throw failure
        }.get(5, TimeUnit.SECONDS)

        result shouldBeInstanceOf LeaderRunResult.ActionFailed::class
        (result as LeaderRunResult.ActionFailed).cause shouldBeEqualTo failure
        election.runIfLeader(lockName) { "복구 성공" } shouldBeEqualTo "복구 성공"
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
                            (LeaderLockHistoryTable.status eq LeaderHistoryStatus.FAILED.name)
                }
                .count()
        }
        failedCount shouldBeEqualTo 1L
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val election = ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 2.seconds,
                    leaseTime = 10.seconds,
                    autoExtend = true,
                ),
            ),
        )
        val executor = Executors.newSingleThreadExecutor()
        val actionStarted = CountDownLatch(1)
        val actionFuture = CompletableFuture<String>()

        try {
            val resultFuture = election.runAsyncIfLeader(lockName, executor) {
                actionStarted.countDown()
                actionFuture
            }
            actionStarted.await(3, TimeUnit.SECONDS).shouldBeTrue()
            executor.shutdown()

            actionFuture.complete("done")

            resultFuture.get(3, TimeUnit.SECONDS) shouldBeEqualTo "done"
            election.runIfLeader(lockName) { "reacquired" } shouldBeEqualTo "reacquired"
        } finally {
            executor.shutdownNow()
        }
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
