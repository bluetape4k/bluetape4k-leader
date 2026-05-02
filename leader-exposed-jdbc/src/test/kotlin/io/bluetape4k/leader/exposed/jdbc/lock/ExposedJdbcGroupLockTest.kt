package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.jdbc.RetryStrategy
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class ExposedJdbcGroupLockTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 빈 DB에서 slot 0 락 획득이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())

        lock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10)).shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 동일 lockName+slot에 이미 활성 락이 있으면 실패한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30))

        val contender = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
        contender.tryLock(Duration.ofMillis(100), Duration.ofSeconds(5)).shouldBeFalse()

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

        lock0.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()
        lock1.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()

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
        expiredLock.tryLock(Duration.ofSeconds(1), Duration.ofMillis(150))

        Thread.sleep(300)

        val newLock = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        newLock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10)).shouldBeTrue()
        newLock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 다른 token으로 unlock 시도 시 예외 없이 경고 로그만 남긴다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val holder = ExposedJdbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))

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
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))
        lock.unlock()

        lock.unlock()
    }
}
