package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.statements.GlobalSuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * R2DBC DB-time 그룹 락의 server-time query budget과 bounded pool 반환을 고정합니다.
 */
class ExposedR2dbcGroupLockQueryBudgetTest : AbstractExposedR2dbcLeaderTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 tryLock은 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
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
    fun `DB 시간 isHeld는 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
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
    fun `DB 시간 extend는 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
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
    fun `DB 시간 minLeaseTime 해제는 CURRENT_TIMESTAMP를 정확히 한 번 읽는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
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
    fun `DB 시간 delete-only 해제는 CURRENT_TIMESTAMP를 읽지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
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
    fun `DB 시간 activeCount는 CURRENT_TIMESTAMP와 조회를 한 번씩 실행한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val election = ExposedR2DbcSuspendLeaderGroupElector(
            db,
            ExposedR2dbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3, useDbTime = true),
            ),
        )

        val sql = recordSql { election.activeCountSuspend(randomName()) }

        sql.currentTimestampCount().shouldBeEqualTo(1)
        sql.size.shouldBeEqualTo(2)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `JVM 시간 모드의 소유권 연산은 CURRENT_TIMESTAMP를 조회하지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
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
    fun `DB 시간 acquire update 경로도 한 번의 server-time 조회를 사용한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val first = ExposedR2dbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )
        first.tryLock(Duration.ZERO, 10.seconds).shouldBeTrue()
        suspendTransaction(db) {
            LeaderGroupLockTable.update(
                where = {
                    (LeaderGroupLockTable.lockName eq lockName) and
                        (LeaderGroupLockTable.slot eq 0)
                },
            ) {
                it[LeaderGroupLockTable.lockedUntil] = Instant.EPOCH
            }
        }

        val takeover = ExposedR2dbcGroupLock(
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
        sql.size.shouldBeEqualTo(2)
        takeover.unlock()
        first.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `DB 시간 오류 뒤 schema 복구 후 다시 획득할 수 있다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(
            db,
            randomName(),
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
        )

        suspendTransaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }
        ExposedR2dbcSchemaInitializer.resetFor(db)
        val failedSql = recordSql {
            lock.tryLock(Duration.ZERO, 10.seconds).shouldBeNull()
        }
        failedSql.currentTimestampCount().shouldBeGreaterOrEqualTo(1)

        ExposedR2dbcSchemaInitializer.ensureSchema(db)
        cleanTables(db)
        val recoveredSql = recordSql {
            lock.tryLock(Duration.ZERO, 10.seconds).shouldBeTrue()
        }
        recoveredSql.currentTimestampCount().shouldBeEqualTo(1)
        lock.unlock()
    }

    @Test
    fun `R2DBC bounded pool은 경합자보다 작아도 모든 커넥션을 반환한다`() = runSuspendIO {
        val options = ConnectionFactoryOptions.parse(
            "r2dbc:h2:mem:///issue667_pool;MODE=MySQL;DB_CLOSE_DELAY=-1"
        )
        val factory = ConnectionFactories.get(options)
        val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(factory)
                .initialSize(0)
                .maxSize(2)
                .maxAcquireTime(JavaDuration.ofSeconds(5))
                .build()
        )
        try {
            val db = R2dbcDatabase.connect(
                pool,
                R2dbcDatabaseConfig.Builder().apply {
                    connectionFactoryOptions = options
                },
            )
            setupDbForPool(db)
            val lockName = randomName()
            val holder = ExposedR2dbcGroupLock(
                db,
                lockName,
                slot = 0,
                retryStrategy = RetryStrategy.Jitter(),
                useDbTime = true,
            )
            holder.tryLock(Duration.ZERO, 30.seconds).shouldBeTrue()

            val start = kotlinx.coroutines.CompletableDeferred<Unit>()
            coroutineScope {
                val jobs = (0 until 16).map {
                    async(Dispatchers.Default) {
                        start.await()
                        val lock = ExposedR2dbcGroupLock(
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
                    }
                }
                start.complete(Unit)
                val results = jobs.awaitAll()
                results.count { it == false }.shouldBeEqualTo(16)
                results.count { it == null }.shouldBeEqualTo(0)
            }
            holder.unlock()

            val metrics = pool.metrics.get()
            metrics.acquiredSize().shouldBeEqualTo(0)
            (metrics.allocatedSize() <= 2).shouldBeTrue()
            metrics.pendingAcquireSize().shouldBeEqualTo(0)
        } finally {
            pool.dispose()
        }
    }

    private suspend fun setupDbForPool(db: R2dbcDatabase) {
        ExposedR2dbcSchemaInitializer.ensureSchema(db)
        cleanTables(db)
    }

    private suspend fun recordSql(block: suspend () -> Any?): List<String> {
        val recorder = SqlRecorder()
        R2dbcTransaction.globalInterceptors += recorder
        return try {
            block()
            recorder.sql.toList()
        } finally {
            R2dbcTransaction.globalInterceptors.remove(recorder)
        }
    }

    private fun List<String>.currentTimestampCount(): Int = count {
        it.contains("CURRENT_TIMESTAMP", ignoreCase = true)
    }

    private class SqlRecorder : GlobalSuspendStatementInterceptor {
        val sql = mutableListOf<String>()

        override suspend fun beforeExecution(
            transaction: R2dbcTransaction,
            context: StatementContext,
        ) {
            sql += context.sql(transaction)
        }
    }
}
