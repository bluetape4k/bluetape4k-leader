package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.exposed.r2dbc.AbstractExposedR2dbcLeaderTest
import io.bluetape4k.leader.exposed.r2dbc.TestR2dbcDB
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class ExposedR2dbcGroupLockTest : AbstractExposedR2dbcLeaderTest() {

    companion object : KLoggingChannel()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 빈 DB에서 슬롯0 첫 번째 락 획득이 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())

        val acquired = lock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired.shouldBeTrue()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 서로 다른 슬롯은 독립적으로 획득된다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val lock0 = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val lock1 = ExposedR2dbcGroupLock(db, lockName, slot = 1, RetryStrategy.Jitter())

        val acquired0 = lock0.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))
        val acquired1 = lock1.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired0.shouldBeTrue()
        acquired1.shouldBeTrue()
        lock0.unlock()
        lock1.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 동일 슬롯에 이미 활성 락이 있으면 경합자가 실패한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val holder = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        holder.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(30))

        val contender = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
        val acquired = contender.tryLock(Duration.ofMillis(100), Duration.ofSeconds(5))

        acquired.shouldBeFalse()
        holder.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - leaseTime 만료 후 동일 슬롯을 다른 인스턴스가 takeover한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()

        val leaseTime = Duration.ofMillis(200)
        val expiredLock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        expiredLock.tryLock(Duration.ofSeconds(1), leaseTime)

        kotlinx.coroutines.delay(leaseTime.toMillis() * 2 + 50)

        val newLock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        val acquired = newLock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        acquired.shouldBeTrue()
        newLock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 동일 token으로 해제 성공 후 재획득이 가능하다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val lock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))

        lock.unlock()

        val reacquire = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Jitter())
        reacquire.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()
        reacquire.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `unlock - 이미 해제된 슬롯 락에 재호출해도 예외가 발생하지 않는다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))
        lock.unlock()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - 슬롯 락 획득 후 true 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10)).shouldBeTrue()

        lock.isHeldByCurrentInstance().shouldBeTrue()

        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - unlock 이후 false 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))
        lock.unlock()

        lock.isHeldByCurrentInstance().shouldBeFalse()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isHeldByCurrentInstance - leaseTime 만료 시 false 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val leaseTime = Duration.ofMillis(150)
        val lock = ExposedR2dbcGroupLock(db, randomName(), slot = 0, RetryStrategy.Jitter())
        lock.tryLock(Duration.ofSeconds(1), leaseTime)

        kotlinx.coroutines.delay(leaseTime.toMillis() * 2)

        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.unlock()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 코루틴 10개 동일 슬롯 경합 시 단 하나만 획득에 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        val jobs = (1..10).map {
            async {
                val lock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(Duration.ofMillis(200), Duration.ofSeconds(5))) {
                    successCount.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    kotlinx.coroutines.delay(300)
                    lock.unlock()
                }
            }
        }
        jobs.awaitAll()

        log.debug { "동일 슬롯 동시 경합 성공 횟수: ${successCount.get()}" }
        successCount.get() shouldBeGreaterOrEqualTo 1
        successCount.get() shouldBeEqualTo 1
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `tryLock - 슬롯0과 슬롯1 동시 경합 시 각각 단 하나씩 성공한다`(testDB: TestR2dbcDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val slot0Count = java.util.concurrent.atomic.AtomicInteger(0)
        val slot1Count = java.util.concurrent.atomic.AtomicInteger(0)

        val jobs0 = (1..5).map {
            async {
                val lock = ExposedR2dbcGroupLock(db, lockName, slot = 0, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(Duration.ofMillis(200), Duration.ofSeconds(5))) {
                    slot0Count.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    kotlinx.coroutines.delay(300)
                    lock.unlock()
                }
            }
        }
        val jobs1 = (1..5).map {
            async {
                val lock = ExposedR2dbcGroupLock(db, lockName, slot = 1, RetryStrategy.Fixed(fixedMs = 10L))
                if (lock.tryLock(Duration.ofMillis(200), Duration.ofSeconds(5))) {
                    slot1Count.incrementAndGet()
                    // action delay > waitTime(200ms) → 나머지 경합자들이 모두 타임아웃
                    kotlinx.coroutines.delay(300)
                    lock.unlock()
                }
            }
        }
        (jobs0 + jobs1).awaitAll()

        log.debug { "슬롯0 성공: ${slot0Count.get()}, 슬롯1 성공: ${slot1Count.get()}" }
        slot0Count.get() shouldBeEqualTo 1
        slot1Count.get() shouldBeEqualTo 1
    }
}
