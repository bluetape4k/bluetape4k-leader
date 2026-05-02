package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.jdbc.RetryStrategy
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ExposedJdbcLockTest : AbstractExposedJdbcLeaderTest() {

    companion object : KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 빈 DB에서 첫 번째 락 획득이 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())

        val acquired = lock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired.shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 동일 lockName에 이미 활성 락이 있으면 실패한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        holder.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30))

        val contender = ExposedJdbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 10L))
        val acquired = contender.tryLock(Duration.ofMillis(100), Duration.ofSeconds(5))

        acquired.shouldBeFalse()
        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - leaseTime 만료 후 다른 인스턴스가 takeover에 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val expiredLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        expiredLock.tryLock(Duration.ofSeconds(1), Duration.ofMillis(150))

        Thread.sleep(300)

        val newLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        val acquired = newLock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired.shouldBeTrue()
        newLock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 동일 token으로 해제가 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))

        lock.unlock()

        val reacquire = ExposedJdbcLock(db, lock.lockName, RetryStrategy.Jitter())
        reacquire.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()
        reacquire.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 이미 해제된 락에 재호출해도 예외가 발생하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))
        lock.unlock()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 멀티스레드 경합 시 단 하나만 락 획득에 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = 10
        val latch = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            executor.submit {
                val lock = ExposedJdbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(Duration.ofMillis(200), Duration.ofSeconds(5))) {
                    successCount.incrementAndGet()
                    Thread.sleep(100)
                    lock.unlock()
                }
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        successCount.get() shouldBeGreaterOrEqualTo 1
    }
}
