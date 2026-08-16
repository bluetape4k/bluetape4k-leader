package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.contract.AbstractMonotonicDeadlineContractTest
import io.bluetape4k.leader.exposed.jdbc.internal.MonotonicDeadline
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.exposed.tables.LeaderLockTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MonotonicDeadlineTest: AbstractMonotonicDeadlineContractTest() {

    private val db: Database by lazy {
        (TestDB.H2.db ?: TestDB.H2.connect()).also(ExposedJdbcSchemaInitializer::ensureSchema)
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
        cleanTables()
        return when (case.target) {
            LockTarget.SINGLE -> observeSingleWait(case)
            LockTarget.GROUP  -> observeGroupWait(case)
        }
    }

    private fun observeSingleWait(case: WaitOutcomeCase): WaitOutcome {
        val lockName = randomLockName()
        val holder = ExposedJdbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 1L))
        check(holder.tryLock(Duration.ZERO, 5.seconds))
        val contender = ExposedJdbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 1L))

        return try {
            observeContender(case) { contender.tryLock(case.waitTime, 5.seconds) }
        } finally {
            holder.unlock()
        }
    }

    private fun observeGroupWait(case: WaitOutcomeCase): WaitOutcome {
        val lockName = randomLockName()
        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 1L))
        check(holder.tryLock(Duration.ZERO, 5.seconds) == true)
        val contender = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 1L))

        return try {
            observeContender(case) { contender.tryLock(case.waitTime, 5.seconds) == true }
        } finally {
            holder.unlock()
        }
    }

    private inline fun observeContender(
        case: WaitOutcomeCase,
        attempt: () -> Boolean,
    ): WaitOutcome {
        return try {
            if (case.boundary == WaitBoundary.CANCELLATION) {
                Thread.currentThread().interrupt()
            }
            check(!attempt()) { "경합 중인 lock을 획득했습니다: $case" }
            WaitOutcome.SKIPPED
        } catch (_: InterruptedException) {
            WaitOutcome.CANCELLED
        } finally {
            Thread.interrupted()
        }
    }

    private fun cleanTables() {
        transaction(db) {
            LeaderLockHistoryTable.deleteAll()
            LeaderLockTable.deleteAll()
            LeaderGroupLockTable.deleteAll()
        }
    }

    private fun randomLockName(): String = "deadline-${UUID.randomUUID()}"
}
