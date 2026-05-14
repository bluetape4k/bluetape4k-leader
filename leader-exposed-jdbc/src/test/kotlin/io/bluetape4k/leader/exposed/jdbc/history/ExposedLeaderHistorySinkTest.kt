package io.bluetape4k.leader.exposed.jdbc.history

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

class ExposedLeaderHistorySinkTest : AbstractExposedJdbcLeaderTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `recordCompleted requires token match when id is present`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val sink = ExposedLeaderHistorySink(db)
        val record = historyRecord(lockName = randomName(), token = "token-1")
        val key = requireNotNull(sink.recordAcquired(record))
        val keyId = requireNotNull(key.id)

        sink.recordCompleted(key.copy(token = "wrong-token"), Instant.now(), 10L)

        val row = transaction(db) {
            LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.id eq keyId }
                .single()
        }
        row[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.ACQUIRED.name
        row[LeaderLockHistoryTable.finishedAt].shouldBeNull()
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
