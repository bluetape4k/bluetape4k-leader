package io.bluetape4k.leader.mongodb

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.bson.Document
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.*
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.random.Random

class MongoLeaderGroupElectionTest: AbstractMongoLeaderTest() {

    companion object: KLogging()

    private val options = MongoLeaderGroupElectionOptions(
        leaderGroupOptions = LeaderGroupElectionOptions(
            maxLeaders = 3,
            waitTime = 30.seconds,
            leaseTime = 60.seconds,
        )
    )
    private val election by lazy { MongoLeaderGroupElector(groupLockCollection, options) }

    @Test
    fun `runIfLeader - 리더로 선출되어 action을 실행하고 결과를 반환한다`() {
        val result = election.runIfLeader(randomName()) { "hello" }

        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - 서로 다른 lockName은 독립적인 슬롯 풀을 가진다`() {
        val result1 = election.runIfLeader(randomName()) { "a" }
        val result2 = election.runIfLeader(randomName()) { "b" }

        result1 shouldBeEqualTo "a"
        result2 shouldBeEqualTo "b"
    }

    @Test
    fun `runIfLeader - 동시 실행 중인 리더 수가 maxLeaders를 초과하지 않는다`() {
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        MultithreadingTester()
            .workers(options.maxLeaders * 4)
            .rounds(2)
            .add {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    Thread.sleep(Random.nextLong(5, 15))
                    currentConcurrent.decrementAndGet()
                }
            }
            .run()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=${options.maxLeaders}" }
        peakConcurrent.get() shouldBeLessOrEqualTo options.maxLeaders
    }

    @Test
    fun `runIfLeader - action 예외 발생 후 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        runCatching { election.runIfLeader(lockName) { throw LeaderGroupElectionException("실패") } }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - maxLeaders 슬롯이 모두 사용 중이면 짧은 waitTime으로 null을 반환한다`() {
        val shortWaitOptions = MongoLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = 1,
                waitTime = 100.milliseconds,
                leaseTime = 10.seconds,
            )
        )
        val singleElection = MongoLeaderGroupElector(groupLockCollection, shortWaitOptions)
        val lockName = randomName()
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            singleElection.runIfLeader(lockName) {
                acquiredLatch.countDown()
                holdLatch.await()
            }
        }

        try {
            acquiredLatch.await(2, TimeUnit.SECONDS)
            val result = singleElection.runIfLeader(lockName) { }
            result.shouldBeNull()
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `runIfLeader - colon-slot-colon을 포함한 lockName은 IllegalArgumentException을 발생시킨다`() {
        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("a:slot:b") { }
        }
    }

    @Test
    fun `state - 초기 상태는 activeCount=0, isEmpty=true, isFull=false이다`() {
        val lockName = randomName()
        val state = election.state(lockName)

        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo options.maxLeaders
        state.activeCount shouldBeEqualTo 0
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
        election.availableSlots(lockName) shouldBeEqualTo options.maxLeaders
    }

    @Test
    fun `state - 슬롯 획득 중 activeCount가 증가하고 해제 후 0으로 돌아온다`() {
        val lockName = randomName()
        val maxLeaders = 3
        // perSlotWait = 5s / 3 ≈ 1.67s → 최악 경우 3.3s 소요, 10s 이내 완료 보장
        val fastOptions = MongoLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(
                maxLeaders = maxLeaders,
                waitTime = 5.seconds,
                leaseTime = 60.seconds,
            )
        )
        val fastElection = MongoLeaderGroupElector(groupLockCollection, fastOptions)
        val acquiredLatch = CountDownLatch(maxLeaders)
        val holdLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(maxLeaders)

        repeat(maxLeaders) {
            executor.submit {
                fastElection.runIfLeader(lockName) {
                    acquiredLatch.countDown()
                    holdLatch.await()
                }
            }
        }

        try {
            acquiredLatch.await(10, TimeUnit.SECONDS)

            val stateWhileHeld = fastElection.state(lockName)
            stateWhileHeld.activeCount shouldBeEqualTo maxLeaders
            stateWhileHeld.isFull.shouldBeTrue()
            fastElection.availableSlots(lockName) shouldBeEqualTo 0
        } finally {
            holdLatch.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        val stateAfter = fastElection.state(lockName)
        stateAfter.activeCount shouldBeEqualTo 0
        stateAfter.isEmpty.shouldBeTrue()
    }

    @Test
    fun `activeCount - 만료된 문서는 집계에서 제외된다`() {
        val lockName = randomName()

        groupLockCollection.insertOne(
            Document("_id", "$lockName:slot:0")
                .append("token", "expired-token")
                .append("expireAt", Date(System.currentTimeMillis() - 60_000))
        )

        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action을 실행하고 결과를 반환한다`() {
        val result = election.runAsyncIfLeader(randomName(), VirtualThreadExecutor) {
            futureOf { "async 성공" }
        }.get(5, TimeUnit.SECONDS)

        result shouldBeEqualTo "async 성공"
    }

    @Test
    fun `runAsyncIfLeader - action 예외 발생 후 슬롯이 반환되어 다음 호출이 성공한다`() {
        val lockName = randomName()

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            futureOf { "복구 성공" }
        }.get(5, TimeUnit.SECONDS)
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runAsyncIfLeader - action 동기 throw 후 슬롯 락 문서가 즉시 삭제된다`() {
        val lockName = randomName()

        assertFailsWith<CompletionException> {
            election.runAsyncIfLeader<Int>(lockName, VirtualThreadExecutor) {
                throw IllegalStateException("action 동기 예외")
            }.join()
        }

        val ids = (0 until options.maxLeaders).map { "$lockName:slot:$it" }
        groupLockCollection.countDocuments(
            com.mongodb.client.model.Filters.`in`("_id", ids)
        ) shouldBeEqualTo 0L
    }
}
