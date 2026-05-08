package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.leader.exposed.AbstractExposedTableTest
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

class LeaderGroupLockTableTest : AbstractExposedTableTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `테이블 생성 및 삭제가 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderGroupLockTable) {
            LeaderGroupLockTable.exists() shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `복합 PK lockName slot 삽입이 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderGroupLockTable) {
            val now = Instant.now()
            // 동일 lockName, 다른 slot — 복합 PK이므로 모두 허용
            repeat(3) { slot ->
                LeaderGroupLockTable.insert {
                    it[lockName] = "group-job"
                    it[LeaderGroupLockTable.slot] = slot
                    it[lockOwner] = "owner-$slot"
                    it[token] = Base58.randomString(8)
                    it[lockedAt] = now
                    it[lockedUntil] = now.plusSeconds(60)
                }
            }

            val count = LeaderGroupLockTable.selectAll()
                .where { LeaderGroupLockTable.lockName eq "group-job" }
                .count()
            count shouldBeEqualTo 3L
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `동일 lockName slot 중복 삽입 시 예외가 발생한다`(testDB: TestDB) {
        withTables(testDB, LeaderGroupLockTable) {
            val now = Instant.now()
            LeaderGroupLockTable.insert {
                it[lockName] = "dup-group"
                it[slot] = 0
                it[token] = Base58.randomString(8)
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            assertFailsWith<Exception> {
                LeaderGroupLockTable.insert {
                    it[lockName] = "dup-group"
                    it[slot] = 0
                    it[token] = Base58.randomString(8)
                    it[lockedAt] = now
                    it[lockedUntil] = now.plusSeconds(60)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `활성 슬롯은 locked_until 이 현재시각 이상인 조건으로만 카운트한다`(testDB: TestDB) {
        withTables(testDB, LeaderGroupLockTable) {
            val now = Instant.now()
            val expired = now.minusSeconds(60) // 최소 60초 이전 — clock drift 방지

            // 만료된 슬롯
            LeaderGroupLockTable.insert {
                it[lockName] = "test-group"
                it[slot] = 0
                it[token] = Base58.randomString(8)
                it[lockedAt] = expired
                it[lockedUntil] = expired.plusSeconds(30) // 이미 만료
            }
            // 활성 슬롯
            LeaderGroupLockTable.insert {
                it[lockName] = "test-group"
                it[slot] = 1
                it[token] = Base58.randomString(8)
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            val activeCount = LeaderGroupLockTable.selectAll()
                .where {
                    LeaderGroupLockTable.lockName eq "test-group" and
                        LeaderGroupLockTable.lockedUntil.greaterEq(now)
                }
                .count()

            activeCount shouldBeEqualTo 1L
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `만료된 슬롯은 신규 획득 가능하다`(testDB: TestDB) {
        withTables(testDB, LeaderGroupLockTable) {
            val now = Instant.now()
            val expired = now.minusSeconds(60) // 최소 60초 이전 — clock drift 방지

            LeaderGroupLockTable.insert {
                it[lockName] = "renew-group"
                it[slot] = 0
                it[token] = Base58.randomString(8)
                it[lockedAt] = expired
                it[lockedUntil] = expired.plusSeconds(30)
            }

            val newToken = Base58.randomString(8)
            val updated = LeaderGroupLockTable.update(
                where = {
                    LeaderGroupLockTable.lockName eq "renew-group" and
                        (LeaderGroupLockTable.slot eq 0) and
                        LeaderGroupLockTable.lockedUntil.less(now)
                }
            ) {
                it[token] = newToken
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            updated shouldBeGreaterThan 0
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `특정 slot 범위 질의가 가능하다`(testDB: TestDB) {
        withTables(testDB, LeaderGroupLockTable) {
            val now = Instant.now()
            val maxLeaders = 5
            repeat(maxLeaders) { s ->
                LeaderGroupLockTable.insert {
                    it[lockName] = "range-group"
                    it[slot] = s
                    it[token] = Base58.randomString(8)
                    it[lockedAt] = now
                    it[lockedUntil] = now.plusSeconds(60)
                }
            }

            val count = LeaderGroupLockTable.selectAll()
                .where {
                    LeaderGroupLockTable.lockName eq "range-group" and
                        (LeaderGroupLockTable.slot greaterEq 0) and
                        (LeaderGroupLockTable.slot less maxLeaders)
                }
                .count()

            count shouldBeEqualTo maxLeaders.toLong()
        }
    }
}
