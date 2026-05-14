package io.bluetape4k.leader.exposed.r2dbc.history

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

class ExposedSuspendLeaderHistorySinkTest : AbstractExposedR2dbcLeaderTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `recordCompleted requires token match when id is present`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val sink = ExposedSuspendLeaderHistorySink(db)
        val record = historyRecord(lockName = randomName(), token = "token-1")
        val key = requireNotNull(sink.recordAcquired(record))
        val keyId = requireNotNull(key.id)

        sink.recordCompleted(key.copy(token = "wrong-token"), Instant.now(), 10L)

        val acquiredCount = suspendTransaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where {
                    (LeaderLockHistoryTable.id eq keyId) and
                            (LeaderLockHistoryTable.status eq LeaderHistoryStatus.ACQUIRED.name)
                }
                .count()
        }
        val completedCount = suspendTransaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where {
                    (LeaderLockHistoryTable.id eq keyId) and
                            (LeaderLockHistoryTable.status eq LeaderHistoryStatus.COMPLETED.name)
                }
                .count()
        }
        acquiredCount shouldBeEqualTo 1L
        completedCount shouldBeEqualTo 0L
    }

    private fun historyRecord(lockName: String, token: String): LeaderLockHistoryRecord {
        val now = Instant.now()
        return LeaderLockHistoryRecord(
            lockName = lockName,
            token = token,
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = now,
            lockedUntil = now.plusSeconds(30),
            nodeId = "test-node",
        )
    }
}
