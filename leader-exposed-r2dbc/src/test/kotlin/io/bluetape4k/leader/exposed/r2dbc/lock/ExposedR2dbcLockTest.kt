package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ExposedR2dbcLockTest: AbstractExposedR2dbcLeaderTest() {

    companion object: KLoggingChannel()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 빈 DB에서 첫 번째 락 획득이 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcLock(db, randomName(), RetryStrategy.Jitter())

        val acquired = lock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired.shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 동일 lockName에 이미 활성 락이 있으면 실패한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
        holder.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30))

        val contender = ExposedR2dbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 10L))
        val acquired = contender.tryLock(Duration.ofMillis(100), Duration.ofSeconds(5))

        acquired.shouldBeFalse()
        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - leaseTime 만료 후 다른 인스턴스가 takeover에 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val leaseTime = Duration.ofMillis(200)
        val expiredLock = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
        expiredLock.tryLock(Duration.ofSeconds(1), leaseTime)

        // leaseTime 만료 대기
        delay(timeMillis = leaseTime.toMillis() * 2 + 50)

        val newLock = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
        val acquired = newLock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired.shouldBeTrue()
        newLock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 동일 token으로 해제가 성공하고 재획득이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))

        lock.unlock()

        val reacquire = ExposedR2dbcLock(db, lock.lockName, RetryStrategy.Jitter())
        reacquire.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()
        reacquire.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 이미 해제된 락에 재호출해도 예외가 발생하지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))
        lock.unlock()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 락 획득 후 true 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()

        lock.isHeldByCurrentInstance().shouldBeTrue()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - unlock 이후 false 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))
        lock.unlock()

        lock.isHeldByCurrentInstance().shouldBeFalse()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 다른 인스턴스 token으로는 false를 반환한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
        holder.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30)).shouldBeTrue()

        val other = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
        other.isHeldByCurrentInstance().shouldBeFalse()

        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - leaseTime 만료 시 false 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val leaseTime = Duration.ofMillis(150)
        val lock = ExposedR2dbcLock(db, randomName(), RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), leaseTime)

        delay(timeMillis = leaseTime.toMillis() * 2)

        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 만료 후 다른 인스턴스 takeover 시 원본 인스턴스는 false 반환`(testDB: TestR2dbcDB) =
        runSuspendIO {
            val db = setupDb(testDB)
            cleanTables(db)
            val lockName = randomName()

            val original = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
            original.tryLock(Duration.ofSeconds(1), Duration.ofMillis(150))
            delay(300.milliseconds)

            val takeover = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
            takeover.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()

            original.isHeldByCurrentInstance().shouldBeFalse()
            takeover.isHeldByCurrentInstance().shouldBeTrue()

            takeover.unlock()
        }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 만료 후 다른 인스턴스가 takeover하면 zombie 인스턴스의 unlock이 현재 락을 해제하지 않는다`(testDB: TestR2dbcDB) =
        runSuspendIO {
            val db = setupDb(testDB)
            cleanTables(db)
            val lockName = randomName()

            val zombie = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
            zombie.tryLock(Duration.ofSeconds(1), Duration.ofMillis(150))
            kotlinx.coroutines.delay(300.milliseconds)  // lease 만료 대기

            val current = ExposedR2dbcLock(db, lockName, RetryStrategy.Jitter())
            current.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()
            current.isHeldByCurrentInstance().shouldBeTrue()

            // 만료된 zombie가 unlock 시도 → token 불일치이므로 current 락이 유지됨
            zombie.unlock()

            current.isHeldByCurrentInstance().shouldBeTrue()
            current.unlock()
        }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 코루틴 10개 경합 시 단 하나만 락 획득에 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        val jobs = (1..10).map {
            async {
                val lock = ExposedR2dbcLock(db, lockName, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(Duration.ofMillis(200), Duration.ofSeconds(5))) {
                    successCount.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    kotlinx.coroutines.delay(300.milliseconds)
                    lock.unlock()
                }
            }
        }
        jobs.awaitAll()

        log.debug { "동시 경합 성공 횟수: ${successCount.get()}" }
        successCount.get() shouldBeGreaterOrEqualTo 1
        successCount.get() shouldBeEqualTo 1
    }
}
