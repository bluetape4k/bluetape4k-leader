package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
