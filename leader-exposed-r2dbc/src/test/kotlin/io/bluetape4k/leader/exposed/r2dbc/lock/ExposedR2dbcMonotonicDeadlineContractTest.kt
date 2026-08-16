package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.contract.AbstractMonotonicDeadlineContractTest
import io.bluetape4k.leader.exposed.r2dbc.internal.MonotonicDeadline
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 문자열 source scan 대신 실제 deadline helper와 H2 lock 경합을 실행해
 * JDBC 형제 모듈과 동일한 wait 결과표를 검증합니다.
 */
class ExposedR2dbcMonotonicDeadlineContractTest: AbstractMonotonicDeadlineContractTest() {

    private val db: R2dbcDatabase by lazy {
        R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///issue681_contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
            user = "",
            password = "",
        )
    }

    override fun createDeadline(waitTime: Duration, ticker: () -> Long): DeadlineProbe {
        val deadline = MonotonicDeadline.fromNow(waitTime, ticker)
        return object: DeadlineProbe {
            override fun remainingNanos(): Long = deadline.remainingNanos()
            override fun remainingMillisForSleep(): Long = deadline.remainingMillisForSleep()
            override fun hasTimeRemaining(): Boolean = deadline.hasTimeRemaining()
        }
    }

    override fun observeWaitOutcome(case: WaitOutcomeCase): WaitOutcome {
        var observed: WaitOutcome? = null
        runSuspendIO {
            ExposedR2dbcSchemaInitializer.ensureSchema(db)
            cleanTables()
            observed = when (case.target) {
                LockTarget.SINGLE -> observeSingleWait(case)
                LockTarget.GROUP  -> observeGroupWait(case)
            }
        }
        return requireNotNull(observed)
    }

    private suspend fun observeSingleWait(case: WaitOutcomeCase): WaitOutcome {
        val lockName = randomLockName()
        val holder = ExposedR2dbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 1L))
        check(holder.tryLock(Duration.ZERO, 5.seconds))
        val contender = ExposedR2dbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 1L))

        return try {
            observeContender(case) { contender.tryLock(case.waitTime, 5.seconds) }
        } finally {
            holder.unlock()
        }
    }

    private suspend fun observeGroupWait(case: WaitOutcomeCase): WaitOutcome {
        val lockName = randomLockName()
        val holder = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 1L))
        check(holder.tryLock(Duration.ZERO, 5.seconds) == true)
        val contender = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 1L))

        return try {
            observeContender(case) { contender.tryLock(case.waitTime, 5.seconds) == true }
        } finally {
            holder.unlock()
        }
    }

    private suspend fun observeContender(
        case: WaitOutcomeCase,
        attempt: suspend () -> Boolean,
    ): WaitOutcome {
        if (case.boundary != WaitBoundary.CANCELLATION) {
            check(!attempt()) { "경합 중인 lock을 획득했습니다: $case" }
            return WaitOutcome.SKIPPED
        }

        return supervisorScope {
            val attemptStarted = CompletableDeferred<Unit>()
            val contender = async {
                attemptStarted.complete(Unit)
                attempt()
            }
            attemptStarted.await()
            delay(1L)
            contender.cancel(CancellationException("deadline contract cancellation"))
            try {
                contender.await()
                error("취소된 lock 경합이 정상 완료되었습니다: $case")
            } catch (_: CancellationException) {
                WaitOutcome.CANCELLED
            }
        }
    }

    private suspend fun cleanTables() {
        suspendTransaction(db) {
            LeaderLockHistoryTable.deleteAll()
            LeaderLockTable.deleteAll()
            LeaderGroupLockTable.deleteAll()
        }
    }

    private fun randomLockName(): String = "deadline-${UUID.randomUUID()}"
}
