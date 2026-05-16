package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.leader.exposed.AbstractExposedTableTest
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import io.bluetape4k.leader.history.LeaderHistoryStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

class LeaderLockHistoryTableTest: AbstractExposedTableTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `테이블 생성 및 삭제가 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockHistoryTable) {
            LeaderLockHistoryTable.exists() shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `이력 레코드 삽입이 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockHistoryTable) {
            val now = Instant.now()
            LeaderHistoryStatus.entries.forEach { s ->
                LeaderLockHistoryTable.insert {
                    it[lockName] = "history-lock"
                    it[token] = Base58.randomString(8)
                    it[lockedUntil] = now.plusSeconds(60)
                    it[status] = s.name
                    it[startedAt] = now
                    it[finishedAt] = if (s != LeaderHistoryStatus.ACQUIRED) now.plusSeconds(1) else null
                    it[durationMs] = if (s != LeaderHistoryStatus.ACQUIRED) 1000L else null
                }
            }

            val count = LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq "history-lock" }
                .count()
            count shouldBeEqualTo LeaderHistoryStatus.entries.size.toLong()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `id가 자동 증가한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockHistoryTable) {
            val now = Instant.now()

            fun insertRow(): Long = LeaderLockHistoryTable.insert {
                it[lockName] = "auto-inc"
                it[token] = Base58.randomString(8)
                it[lockedUntil] = now.plusSeconds(60)
                it[status] = LeaderHistoryStatus.ACQUIRED.name
                it[startedAt] = now
            }[LeaderLockHistoryTable.id]

            val id1 = insertRow()
            val id2 = insertRow()

            id2 shouldBeGreaterThan id1
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `lockName + startedAt 인덱스를 활용한 조회가 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockHistoryTable) {
            val now = Instant.now()
            val name = "indexed-lock"

            repeat(3) { i ->
                LeaderLockHistoryTable.insert {
                    it[lockName] = name
                    it[token] = Base58.randomString(8)
                    it[lockedUntil] = now.plusSeconds(60)
                    it[status] = LeaderHistoryStatus.COMPLETED.name
                    it[startedAt] = now.plusSeconds(i.toLong())
                }
            }

            val rows = LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.lockName eq name }
                .toList()
            rows.size shouldBeEqualTo 3
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `finishedAt durationMs nullable 컬럼이 정상 동작한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockHistoryTable) {
            val now = Instant.now()

            val id = LeaderLockHistoryTable.insert {
                it[lockName] = "nullable-test"
                it[token] = Base58.randomString(8)
                it[lockedUntil] = now.plusSeconds(60)
                it[status] = LeaderHistoryStatus.ACQUIRED.name
                it[startedAt] = now
                // finishedAt, durationMs 미설정 — null 허용
            }[LeaderLockHistoryTable.id]

            val row = LeaderLockHistoryTable.selectAll()
                .where { LeaderLockHistoryTable.id eq id }
                .single()

            row[LeaderLockHistoryTable.finishedAt].shouldBeNull()
            row[LeaderLockHistoryTable.durationMs].shouldBeNull()
            row[LeaderLockHistoryTable.slot].shouldBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `30일 이전 데이터 삭제가 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockHistoryTable) {
            val now = Instant.now()
            // DB-native INTERVAL 대신 Kotlin Instant 파라미터 바인딩 — H2/PG/MySQL INTERVAL 문법 불일치 회피
            val cutoff = now.minus(31, ChronoUnit.DAYS)
            val oldTime = now.minus(35, ChronoUnit.DAYS)

            // 35일 전 레코드 삽입
            repeat(3) {
                LeaderLockHistoryTable.insert {
                    it[lockName] = "old-lock"
                    it[token] = Base58.randomString(8)
                    it[lockedUntil] = oldTime.plusSeconds(60)
                    it[status] = LeaderHistoryStatus.COMPLETED.name
                    it[startedAt] = oldTime
                }
            }
            // 최근 레코드 삽입
            LeaderLockHistoryTable.insert {
                it[lockName] = "recent-lock"
                it[token] = Base58.randomString(8)
                it[lockedUntil] = now.plusSeconds(60)
                it[status] = LeaderHistoryStatus.COMPLETED.name
                it[startedAt] = now
            }

            val deleted = LeaderLockHistoryTable.deleteWhere {
                LeaderLockHistoryTable.startedAt less cutoff
            }

            deleted shouldBeEqualTo 3

            // 최근 레코드는 남아 있어야 함
            val remaining = LeaderLockHistoryTable.selectAll().count()
            remaining shouldBeEqualTo 1L
        }
    }
}
