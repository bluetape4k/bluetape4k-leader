package io.bluetape4k.leader.mongodb

import com.mongodb.client.model.Filters
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoLeaderElectionTest: AbstractMongoLeaderTest() {

    companion object: KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`() {
        val election = MongoLeaderElector(lockCollection)

        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 동일 lockName에 여러 스레드 동시 접근 시 최소 1개 이상 성공한다`() {
        val lockName = randomName()
        val options = MongoLeaderElectionOptions(
            leaderOptions = LeaderElectionOptions(
                waitTime = 5.seconds,
                leaseTime = 10.seconds,
            )
        )
        val election = MongoLeaderElector(lockCollection, options)
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
        val election = MongoLeaderElector(lockCollection)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("   ") { }
        }
    }

    @Test
    fun `runIfLeader - dot을 포함한 lockName은 IllegalArgumentException을 발생시킨다`() {
        val election = MongoLeaderElector(lockCollection)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("a.b") { }
        }
    }

    @Test
    fun `runIfLeader - colon-slot-colon을 포함한 lockName은 IllegalArgumentException을 발생시킨다`() {
        val election = MongoLeaderElector(lockCollection)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("a:slot:b") { }
        }
    }

    @Test
    fun `runIfLeader - action 예외 발생 시 예외가 전파되고 lock document가 삭제된다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(lockCollection)

        assertFailsWith<LeaderElectionException> {
            election.runIfLeader(lockName) {
                throw LeaderElectionException("테스트 예외")
            }
        }

        val remaining = lockCollection.countDocuments(Filters.eq("_id", lockName))
        remaining shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeader - action 예외 발생 후에도 lock이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(lockCollection)

        runCatching { election.runIfLeader(lockName) { throw LeaderElectionException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 빠른 종료 시 minLeaseTime 동안 Mongo TTL 로 락을 보존한다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(
            lockCollection,
            MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 100.milliseconds,
                    leaseTime = 2.seconds,
                    minLeaseTime = 300.milliseconds,
                )
            )
        )

        election.runIfLeader(lockName) { "done" } shouldBeEqualTo "done"
        election.runIfLeader(lockName) { "too-early" }.shouldBeNull()

        Thread.sleep(450)

        election.runIfLeader(lockName) { "after-min" } shouldBeEqualTo "after-min"
    }

    @Test
    fun `runIfLeader - 락 보유 중 짧은 waitTime으로 호출하면 null을 반환한다`() {
        val lockName = randomName()
        val holderLock = MongoLock(lockCollection, lockName)
        holderLock.tryLock(2.seconds, 10.seconds)

        try {
            val shortWaitOptions = MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 100.milliseconds,
                    leaseTime = 5.seconds,
                )
            )
            val election = MongoLeaderElector(lockCollection, shortWaitOptions)

            val result = election.runIfLeader(lockName) { "실행하면 안 됨" }

            result.shouldBeNull()
        } finally {
            holderLock.unlock()
        }
    }

    @Test
    fun `runIfLeader - leaseTime 만료 후 takeover가 성공한다`() {
        val lockName = randomName()
        val holderLock = MongoLock(lockCollection, lockName)
        holderLock.tryLock(1.seconds, 200.milliseconds)

        Thread.sleep(350)

        val election = MongoLeaderElector(
            lockCollection,
            MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = 2.seconds,
                    leaseTime = 10.seconds,
                )
            )
        )
        val result = election.runIfLeader(lockName) { "takeover 성공" }
        result shouldBeEqualTo "takeover 성공"
    }

    @Test
    fun `unlock - 토큰 불일치 시 예외 없이 경고 로그만 남긴다`() {
        val lockName = randomName()
        val lock = MongoLock(lockCollection, lockName)
        lock.tryLock(1.seconds, 10.seconds)

        lockCollection.deleteOne(Filters.eq("_id", lockName))

        lock.unlock()

        val election = MongoLeaderElector(lockCollection)
        val result = election.runIfLeader(lockName) { "재획득 성공" }
        result shouldBeEqualTo "재획득 성공"
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action을 실행하고 결과를 반환한다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(lockCollection)

        val result = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async 성공"
    }

    @Test
    fun `runAsyncIfLeader - action이 CF 반환 전 throw하면 CompletionException으로 전파되고 락이 해제된다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(lockCollection)

        assertFailsWith<CompletionException> {
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

    @Test
    fun `unlock - takeover 후 원소유자가 unlock 시도해도 신규 소유자의 락 문서는 유지된다`() {
        val lockName = randomName()
        val oldLock = MongoLock(lockCollection, lockName)
        oldLock.tryLock(1.seconds, 200.milliseconds).shouldBeTrue()

        Thread.sleep(350)

        val newLock = MongoLock(lockCollection, lockName)
        newLock.tryLock(2.seconds, 10.seconds).shouldBeTrue()

        oldLock.unlock()

        newLock.isHeldByCurrentInstance().shouldBeTrue()
        lockCollection.countDocuments(Filters.eq("_id", lockName)) shouldBeEqualTo 1L

        newLock.unlock()
    }

    @Test
    fun `runAsyncIfLeader - 정상 완료 후 락 문서가 삭제된다`() {
        val lockName = randomName()
        val election = MongoLeaderElector(lockCollection)

        election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            futureOf { "ok" }
        }.get(5, TimeUnit.SECONDS) shouldBeEqualTo "ok"

        lockCollection.countDocuments(Filters.eq("_id", lockName)) shouldBeEqualTo 0L
    }
}
