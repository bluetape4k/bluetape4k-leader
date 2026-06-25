package io.bluetape4k.leader.exposed.tables

import io.bluetape4k.codec.Base58
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.leader.exposed.AbstractExposedTableTest
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

class LeaderLockTableTest: AbstractExposedTableTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `테이블 생성 및 삭제가 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockTable) {
            LeaderLockTable.exists() shouldBeEqualTo true
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `락 레코드 삽입 및 조회가 성공한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockTable) {
            val name = "test-lock"
            val tok = Base58.randomString(8)
            val now = Instant.now()

            LeaderLockTable.insert {
                it[lockName] = name
                it[lockOwner] = "owner-1"
                it[token] = tok
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            val row = LeaderLockTable.selectAll()
                .where { LeaderLockTable.lockName eq name }
                .single()

            row[LeaderLockTable.lockName] shouldBeEqualTo name
            row[LeaderLockTable.token] shouldBeEqualTo tok
            row[LeaderLockTable.lockOwner] shouldBeEqualTo "owner-1"
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `동일 lockName 중복 삽입 시 예외가 발생한다`(testDB: TestDB) {
        withTables(testDB, LeaderLockTable) {
            val name = "dup-lock"
            val now = Instant.now()

            LeaderLockTable.insert {
                it[lockName] = name
                it[token] = Base58.randomString(8)
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            assertFailsWith<Exception> {
                LeaderLockTable.insert {
                    it[lockName] = name
                    it[token] = Base58.randomString(8)
                    it[lockedAt] = now
                    it[lockedUntil] = now.plusSeconds(60)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `만료된 락을 갱신할 수 있다`(testDB: TestDB) {
        withTables(testDB, LeaderLockTable) {
            val name = "expired-lock"
            val now = Instant.now()
            val expired = now.minusSeconds(60) // 최소 60초 이전 사용 (clock drift 방지)

            LeaderLockTable.insert {
                it[lockName] = name
                it[token] = Base58.randomString(8)
                it[lockedAt] = expired
                it[lockedUntil] = expired.plusSeconds(30) // 이미 만료
            }

            val newToken = Base58.randomString(8)
            val updated = LeaderLockTable.update(
                where = { LeaderLockTable.lockName eq name and (LeaderLockTable.lockedUntil less now) }
            ) {
                it[token] = newToken
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            updated shouldBeGreaterThan 0

            val row = LeaderLockTable.selectAll()
                .where { LeaderLockTable.lockName eq name }
                .single()
            row[LeaderLockTable.token] shouldBeEqualTo newToken
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `token 불일치 시 삭제되지 않는다`(testDB: TestDB) {
        withTables(testDB, LeaderLockTable) {
            val name = "token-test"
            val correctToken = Base58.randomString(8)
            val now = Instant.now()

            LeaderLockTable.insert {
                it[lockName] = name
                it[token] = correctToken
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            val deleted = LeaderLockTable.deleteWhere {
                LeaderLockTable.lockName eq name and
                        (LeaderLockTable.token eq "wrong-token-value")
            }

            deleted shouldBeEqualTo 0

            // 레코드 여전히 존재
            val row = LeaderLockTable.selectAll()
                .where { LeaderLockTable.lockName eq name }
                .singleOrNull()
            row.shouldNotBeNull()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `timestamp 정밀도가 밀리초 단위로 보존된다`(testDB: TestDB) {
        withTables(testDB, LeaderLockTable) {
            val name = "ts-test"
            // 밀리초 단위로 truncate (H2=나노초, PG/MySQL=마이크로초 정밀도 차이 회피)
            val now = Instant.ofEpochMilli(Instant.now().toEpochMilli())

            LeaderLockTable.insert {
                it[lockName] = name
                it[token] = Base58.randomString(8)
                it[lockedAt] = now
                it[lockedUntil] = now.plusSeconds(60)
            }

            val row = LeaderLockTable.selectAll()
                .where { LeaderLockTable.lockName eq name }
                .single()

            // 밀리초 단위 비교
            val storedMs = row[LeaderLockTable.lockedAt].toEpochMilli()
            storedMs shouldBeEqualTo now.toEpochMilli()
        }
    }
}
