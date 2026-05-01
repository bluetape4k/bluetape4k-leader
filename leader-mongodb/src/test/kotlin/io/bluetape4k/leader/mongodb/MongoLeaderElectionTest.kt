package io.bluetape4k.leader.mongodb

import com.mongodb.client.model.Filters
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoLeaderElectionTest : AbstractMongoLeaderTest() {

    companion object : KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`() {
        val election = MongoLeaderElection(lockCollection)

        val result = election.runIfLeader(randomLockName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 동일 lockName에 여러 스레드 동시 접근 시 최소 1개 이상 성공한다`() {
        val lockName = randomLockName()
        val options = MongoLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = Duration.ofSeconds(5),
                leaseTime = Duration.ofSeconds(10),
            )
        )
        val election = MongoLeaderElection(lockCollection, options)
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(10)
            .rounds(1)
            .add {
                election.runIfLeader(lockName) {
                    Thread.sleep(10)
                    successCount.incrementAndGet()
                }
                log.debug { "successCount=${successCount.get()}" }
            }
            .run()

        successCount.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `runIfLeader - blank lockName은 IllegalArgumentException을 발생시킨다`() {
        val election = MongoLeaderElection(lockCollection)

        assertThrows<IllegalArgumentException> {
            election.runIfLeader("   ") { }
        }
    }

    @Test
    fun `runIfLeader - dot을 포함한 lockName은 IllegalArgumentException을 발생시킨다`() {
        val election = MongoLeaderElection(lockCollection)

        assertThrows<IllegalArgumentException> {
            election.runIfLeader("a.b") { }
        }
    }

    @Test
    fun `runIfLeader - colon-slot-colon을 포함한 lockName은 IllegalArgumentException을 발생시킨다`() {
        val election = MongoLeaderElection(lockCollection)

        assertThrows<IllegalArgumentException> {
            election.runIfLeader("a:slot:b") { }
        }
    }

    @Test
    fun `runIfLeader - action 예외 발생 시 예외가 전파되고 lock document가 삭제된다`() {
        val lockName = randomLockName()
        val election = MongoLeaderElection(lockCollection)

        assertThrows<RuntimeException> {
            election.runIfLeader(lockName) {
                throw RuntimeException("테스트 예외")
            }
        }

        val remaining = lockCollection.countDocuments(Filters.eq("_id", lockName))
        remaining shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeader - action 예외 발생 후에도 lock이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomLockName()
        val election = MongoLeaderElection(lockCollection)

        runCatching { election.runIfLeader(lockName) { throw RuntimeException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 락 보유 중 짧은 waitTime으로 호출하면 null을 반환한다`() {
        val lockName = randomLockName()
        val holderLock = MongoLock(lockCollection, lockName)
        holderLock.tryLock(Duration.ofSeconds(2), Duration.ofSeconds(10))

        try {
            val shortWaitOptions = MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ofMillis(100),
                    leaseTime = Duration.ofSeconds(5),
                )
            )
            val election = MongoLeaderElection(lockCollection, shortWaitOptions)

            val result = election.runIfLeader(lockName) { "실행하면 안 됨" }

            result.shouldBeNull()
        } finally {
            holderLock.unlock()
        }
    }

    @Test
    fun `runIfLeader - leaseTime 만료 후 takeover가 성공한다`() {
        val lockName = randomLockName()
        val holderLock = MongoLock(lockCollection, lockName)
        holderLock.tryLock(Duration.ofSeconds(1), Duration.ofMillis(200))

        Thread.sleep(350)

        val election = MongoLeaderElection(
            lockCollection,
            MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ofSeconds(2),
                    leaseTime = Duration.ofSeconds(10),
                )
            )
        )
        val result = election.runIfLeader(lockName) { "takeover 성공" }
        result shouldBeEqualTo "takeover 성공"
    }

    @Test
    fun `unlock - 토큰 불일치 시 예외 없이 경고 로그만 남긴다`() {
        val lockName = randomLockName()
        val lock = MongoLock(lockCollection, lockName)
        lock.tryLock(Duration.ofSeconds(1), Duration.ofSeconds(10))

        lockCollection.deleteOne(Filters.eq("_id", lockName))

        lock.unlock()

        val election = MongoLeaderElection(lockCollection)
        val result = election.runIfLeader(lockName) { "재획득 성공" }
        result shouldBeEqualTo "재획득 성공"
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action을 실행하고 결과를 반환한다`() {
        val lockName = randomLockName()
        val election = MongoLeaderElection(lockCollection)

        val result = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async 성공"
    }

    @Test
    fun `runAsyncIfLeader - action이 CF 반환 전 throw하면 CompletionException으로 전파되고 락이 해제된다`() {
        val lockName = randomLockName()
        val election = MongoLeaderElection(lockCollection)

        assertThrows<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `ensureIndexes - resetEnsuredFor 후 재호출 시 에러 없이 완료된다`() {
        val namespace = lockCollection.namespace.fullName
        MongoLock.resetEnsuredFor(namespace)

        MongoLock.ensureIndexes(lockCollection)
    }

    @Test
    fun `ensureIndexes - 연속 호출 시 멱등하게 동작한다`() {
        MongoLock.ensureIndexes(lockCollection)
        MongoLock.ensureIndexes(lockCollection)
    }
}
