package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.ExposedLeaderConstants.GROUP_LOCK_TABLE_NAME
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.tables.LeaderGroupLockTable
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExposedJdbcGroupLockTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 빈 DB에서 slot 0 락 획득이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())

        lock.tryLock(2.seconds, 10.seconds).shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 동일 lockName+slot에 이미 활성 락이 있으면 실패한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(1.seconds, 30.seconds)

        val contender = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
        contender.tryLock(100.milliseconds, 5.seconds).shouldBeFalse()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - group 재시도 대기 interrupt 를 재전파한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(1.seconds, 30.seconds)
        val contender = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))

        try {
            Thread.currentThread().interrupt()

            assertFailsWith<InterruptedException> {
                contender.tryLock(1.seconds, 5.seconds)
            }
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
            holder.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 다른 slot은 동시에 획득 가능하다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val lock0 = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val lock1 = ExposedJdbcGroupLock(db, lockName, slot = 1, RetryStrategy.Jitter())

        lock0.tryLock(1.seconds, 10.seconds).shouldBeTrue()
        lock1.tryLock(1.seconds, 10.seconds).shouldBeTrue()

        lock0.unlock()
        lock1.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - leaseTime 만료 후 다른 인스턴스가 takeover에 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val expiredLock = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        expiredLock.tryLock(1.seconds, 150.milliseconds)

        val newLock = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val deadlineNanos = System.nanoTime() + 2.seconds.inWholeNanoseconds
        var acquired = false

        while (System.nanoTime() < deadlineNanos && !acquired) {
            acquired = newLock.tryLock(50.milliseconds, 10.seconds) == true
            if (!acquired) {
                Thread.yield()
            }
        }

        acquired.shouldBeTrue()
        if (acquired) {
            newLock.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - DB 시간 모드에서는 JVM clock skew가 슬롯 소유권을 깨뜨리지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val oldJvmClock = Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC)
        val futureJvmClock = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC)

        val holder = ExposedJdbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            useDbTime = true,
            clock = oldJvmClock,
        )
        holder.tryLock(1.seconds, 30.seconds).shouldBeTrue()

        val contender = ExposedJdbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Fixed(fixedMs = 10L),
            useDbTime = true,
            clock = futureJvmClock,
        )
        contender.tryLock(100.milliseconds, 5.seconds).shouldBeFalse()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 만료된 동일 token에는 minLeaseTime을 적용하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val now = Instant.parse("2026-01-02T03:04:05Z")
        val lock = ExposedJdbcGroupLock(
            db,
            lockName,
            slot = 0,
            retryStrategy = RetryStrategy.Jitter(),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        lock.tryLock(1.seconds, 30.seconds).shouldBeTrue()

        val expiredUntil = now.minusSeconds(1)
        transaction(db) {
            LeaderGroupLockTable.update(
                where = { (LeaderGroupLockTable.lockName eq lockName) and (LeaderGroupLockTable.slot eq 0) },
            ) {
                it[LeaderGroupLockTable.lockedUntil] = expiredUntil
            }
        }

        lock.unlock(minLeaseTime = 1.minutes)

        transaction(db) {
            LeaderGroupLockTable
                .selectAll()
                .where { (LeaderGroupLockTable.lockName eq lockName) and (LeaderGroupLockTable.slot eq 0) }
                .single()[LeaderGroupLockTable.lockedUntil]
        }.shouldBeEqualTo(expiredUntil)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 다른 token으로 unlock 시도 시 예외 없이 경고 로그만 남긴다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(1.seconds, 10.seconds)

        val stranger = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        stranger.unlock()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 이미 해제된 락에 재호출해도 예외가 발생하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)
        lock.unlock()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlockAndReport - RELEASED NOT_HELD FAILED를 구분한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        try {
            val lock = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
            lock.tryLock(1.seconds, 10.seconds).shouldBeTrue()

            lock.unlockAndReport() shouldBeEqualTo ExposedJdbcUnlockOutcome.RELEASED
            lock.unlockAndReport() shouldBeEqualTo ExposedJdbcUnlockOutcome.NOT_HELD

            transaction(db) { exec("DROP TABLE $GROUP_LOCK_TABLE_NAME") }
            ExposedJdbcSchemaInitializer.resetFor(db)
            val failed = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
            failed.unlockAndReport() shouldBeEqualTo ExposedJdbcUnlockOutcome.FAILED
        } finally {
            ExposedJdbcSchemaInitializer.ensureSchema(db)
            cleanTables(db)
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 락 획득 후 true를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 30.seconds)

        lock.isHeldByCurrentInstance().shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 다른 인스턴스 token으로는 false를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(1.seconds, 30.seconds)

        val other = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        other.isHeldByCurrentInstance().shouldBeFalse()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - unlock 후 false를 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 30.seconds)
        lock.unlock()

        lock.isHeldByCurrentInstance().shouldBeFalse()
    }

    @Test
    fun `생성자 - slot 음수는 IllegalArgumentException 발생`() {
        // DB 연결 없이 init 검증만 확인 — connectDb 불필요
        assertFailsWith<IllegalArgumentException> {
            ExposedJdbcGroupLock(
                org.jetbrains.exposed.v1.jdbc.Database.connect("jdbc:h2:mem:slot-validate;DB_CLOSE_DELAY=-1"),
                lockName = "slot-validate",
                slot = -1,
                retryStrategy = RetryStrategy.Jitter(),
            )
        }
    }
}
