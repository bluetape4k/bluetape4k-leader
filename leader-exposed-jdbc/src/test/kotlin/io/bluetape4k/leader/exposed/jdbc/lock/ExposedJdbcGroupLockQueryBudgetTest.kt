package io.bluetape4k.leader.exposed.jdbc.lock

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * DB-time 그룹 락이 소유권 경계마다 한 번만 server-time을 읽고, delete-only 해제에서는 읽지 않는지 고정합니다.
 * 또한 JDBC connection pool보다 많은 경합자가 커넥션을 모두 반환하는지 확인합니다.
 */
class ExposedJdbcGroupLockQueryBudgetTest : AbstractExposedJdbcLeaderTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 tryLock은 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )

        val sql = recordSql {
            lock.tryLock(Duration.ZERO, 10.seconds)
        }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(4)
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 isHeld는 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        lock.tryLock(Duration.ZERO, 10.seconds)

        val sql = recordSql { lock.isHeldByCurrentInstance() }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(2)
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 extend는 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        lock.tryLock(Duration.ZERO, 10.seconds)

        val sql = recordSql { lock.extendDetailed(10.seconds) }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(2)
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 minLeaseTime 해제는 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        lock.tryLock(Duration.ZERO, 10.seconds)

        val sql = recordSql {
            lock.unlock(minLeaseTime = 1.seconds, acquiredAtNanos = System.nanoTime())
        }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(2)
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 delete-only 해제는 CURRENT_TIMESTAMP를 읽지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        lock.tryLock(Duration.ZERO, 10.seconds)

        val sql = recordSql { lock.unlock() }

        sql.currentTimestampCount().shouldBeEqualTo(0)
        sql.size.shouldBeEqualTo(1)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 activeCount는 CURRENT_TIMESTAMP와 조회를 한 번씩 실행한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val election = ExposedJdbcLeaderGroupElector(
            db,
            ExposedJdbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3, useDbTime = true),
            ),
        )

        val sql = recordSql { election.activeCount(randomName()) }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(2)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `JVM 시간 모드의 소유권 연산은 CURRENT_TIMESTAMP를 조회하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = false,
        )

        val sql = recordSql {
            lock.tryLock(Duration.ZERO, 10.seconds).shouldBeTrue()
        }

        sql.currentTimestampCount().shouldBeEqualTo(0)
        sql.size.shouldBeEqualTo(3)
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 acquire update 경로도 한 번의 server-time 조회를 사용한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val first = ExposedJdbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        first.tryLock(Duration.ZERO, 10.seconds).shouldBeTrue()
        transaction(db) {
            LeaderGroupLockTable.update(
                where = {
                    (LeaderGroupLockTable.lockName eq lockName) and
                        (LeaderGroupLockTable.slot eq 0)
                },
            ) {
                it[LeaderGroupLockTable.lockedUntil] = Instant.EPOCH
            }
        }

        val takeover = ExposedJdbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        val sql = recordSql {
            takeover.tryLock(Duration.ZERO, 10.seconds).shouldBeTrue()
        }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(3)
        takeover.unlock()
        first.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 오류 뒤 schema 복구 후 다시 획득할 수 있다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )

        transaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }
        ExposedJdbcSchemaInitializer.resetFor(db)
        val failedSql = recordSql {
            lock.tryLock(Duration.ZERO, 10.seconds).shouldBeNull()
        }
        failedSql.currentTimestampCount().shouldBeGreaterOrEqualTo(1)

        ExposedJdbcSchemaInitializer.ensureSchema(db)
        cleanTables(db)
        val recoveredSql = recordSql {
            lock.tryLock(Duration.ZERO, 10.seconds).shouldBeTrue()
        }
        recoveredSql.currentTimestampCount().shouldBeEqualTo(1)
        lock.unlock()
    }

    @Test
    fun `Hikari bounded pool은 경합자보다 작아도 모든 커넥션을 반환한다`() {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:${randomName()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                driverClassName = "org.h2.Driver"
                maximumPoolSize = 2
                minimumIdle = 0
                connectionTimeout = 5_000
            }
        )
        val executor = Executors.newFixedThreadPool(16)
        val lockName = randomName()
        try {
            val db = Database.connect(dataSource)
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            cleanTables(db)
            val holder = ExposedJdbcGroupLock(
                db,
                lockName,
                slot = 0,
                retryStrategy = RetryStrategy.Jitter(),
                useDbTime = true,
            )
            holder.tryLock(Duration.ZERO, 30.seconds).shouldBeTrue()

            val contenders = 16
            val barrier = CyclicBarrier(contenders + 1)
            val futures = (0 until contenders).map {
                executor.submit(Callable {
                    barrier.await()
                    val lock = ExposedJdbcGroupLock(
                        db,
                        lockName,
                        slot = 0,
                        retryStrategy = RetryStrategy.Jitter(),
                        useDbTime = true,
                    )
                    val acquired = lock.tryLock(300.milliseconds, 30.seconds)
                    if (acquired == true) {
                        lock.unlock()
                    }
                    acquired
                })
            }
            barrier.await()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            results.count { it == false }.shouldBeEqualTo(contenders)
            results.count { it == null }.shouldBeEqualTo(0)
            holder.unlock()

            val pool = dataSource.hikariPoolMXBean.shouldNotBeNull()
            pool.activeConnections.shouldBeEqualTo(0)
            (pool.totalConnections <= 2).shouldBeTrue()
        } finally {
            executor.shutdownNow()
            dataSource.close()
        }
    }

    private fun recordSql(block: () -> Any?): List<String> {
        val recorder = SqlRecorder()
        JdbcTransaction.globalInterceptors += recorder
        return try {
            block()
            recorder.sql.toList()
        } finally {
            JdbcTransaction.globalInterceptors.remove(recorder)
        }
    }

    private fun List<String>.currentTimestampCount(): Int = count {
        it.contains("CURRENT_TIMESTAMP", ignoreCase = true)
    }

    private class SqlRecorder : GlobalStatementInterceptor {
        val sql = mutableListOf<String>()

        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
            sql += context.sql(transaction)
        }
    }
}
