package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.AbstractExposedJdbcLeaderTest
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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

        val acquired = lock.tryLock(2.seconds, 10.seconds)

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
        holder.tryLock(1.seconds, 30.seconds)

        val contender = ExposedJdbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 10L))
        val acquired = contender.tryLock(100.milliseconds, 5.seconds)

        acquired.shouldBeFalse()
        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - leaseTime 만료 후 다른 인스턴스가 takeover에 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val leaseTime = 150.milliseconds
        val expiredLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        expiredLock.tryLock(1.seconds, leaseTime)

        val newLock = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        val deadlineNanos = System.nanoTime() + 2.seconds.inWholeNanoseconds
        val pollIntervalMs = 25L
        var acquired = false

        while (System.nanoTime() < deadlineNanos && !acquired) {
            acquired = newLock.tryLock(Duration.ZERO, 10.seconds)
            if (!acquired) {
                Thread.sleep(pollIntervalMs)
            }
        }

        acquired.shouldBeTrue()
        if (acquired) {
            newLock.unlock()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 동일 token으로 해제가 성공한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)

        lock.unlock()

        val reacquire = ExposedJdbcLock(db, lock.lockName, RetryStrategy.Jitter())
        reacquire.tryLock(1.seconds, 10.seconds).shouldBeTrue()
        reacquire.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 이미 해제된 락에 재호출해도 예외가 발생하지 않는다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)
        lock.unlock()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 락 획득 후 true 반환`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds).shouldBeTrue()

        lock.isHeldByCurrentInstance().shouldBeTrue()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - unlock 이후 false 반환`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(1.seconds, 10.seconds)
        lock.unlock()

        lock.isHeldByCurrentInstance().shouldBeFalse()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - leaseTime 만료 시 false 반환`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lock = ExposedJdbcLock(db, randomName(), RetryStrategy.Jitter())
        val leaseTime = 150.milliseconds
        lock.tryLock(1.seconds, leaseTime)

        Thread.sleep(leaseTime.inWholeMilliseconds * 2)

        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 만료 후 다른 인스턴스 takeover 시 원본 인스턴스는 false 반환`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val original = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        original.tryLock(1.seconds, 150.milliseconds)
        Thread.sleep(250)

        val takeover = ExposedJdbcLock(db, lockName, RetryStrategy.Jitter())
        takeover.tryLock(1.seconds, 10.seconds).shouldBeTrue()

        // 원본 인스턴스 token은 더 이상 유효하지 않음
        original.isHeldByCurrentInstance().shouldBeFalse()
        // 새 인스턴스는 자신 token으로 보유 중
        takeover.isHeldByCurrentInstance().shouldBeTrue()

        takeover.unlock()
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
                if (lock.tryLock(200.milliseconds, 5.seconds)) {
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
