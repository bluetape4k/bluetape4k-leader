package io.bluetape4k.leader.mongodb

import com.mongodb.client.model.Filters
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class MongoSuspendLeaderElectionTest: AbstractMongoLeaderTest() {

    companion object: KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action을 실행하고 결과를 반환한다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)
        val lockName = randomName()

        val result = election.runIfLeader(lockName) { "success" }

        result shouldBeEqualTo "success"
    }

    @Test
    fun `runIfLeader - 코루틴 10개 동시 실행 시 최소 1개 이상 성공한다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)
        val lockName = randomName()
        val successCount = AtomicInteger(0)

        val jobs = (1..10).map {
            async {
                election.runIfLeader(lockName) {
                    successCount.incrementAndGet()
                    delay(10.milliseconds)
                }
            }
        }
        jobs.awaitAll()

        log.debug { "동시 실행 성공 횟수: ${successCount.get()}" }
        successCount.get() shouldBeGreaterOrEqualTo 1
    }

    @Test
    fun `runIfLeader - 빈 lockName은 IllegalArgumentException을 던진다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("") { "never" }
        }
    }

    @Test
    fun `runIfLeader - 점이 포함된 lockName은 IllegalArgumentException을 던진다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("a.b") { "never" }
        }
    }

    @Test
    fun `runIfLeader - 콜론슬롯콜론이 포함된 lockName은 IllegalArgumentException을 던진다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)

        assertFailsWith<IllegalArgumentException> {
            election.runIfLeader("a:slot:b") { "never" }
        }
    }

    @Test
    fun `runIfLeader - 락 보유 중 짧은 waitTime으로 경합하면 null을 반환한다`() = runSuspendIO {
        val lockName = randomName()
        val holdingElection = MongoSuspendLeaderElection(coroutineLockCollection)
        val shortWaitElection = MongoSuspendLeaderElection(
            coroutineLockCollection,
            MongoLeaderElectionOptions(
                leaderOptions = LeaderElectionOptions(
                    waitTime = Duration.ofMillis(50),
                    leaseTime = Duration.ofSeconds(10),
                )
            )
        )

        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder = launch {
            holdingElection.runIfLeader(lockName) {
                acquired.complete(Unit)
                release.await()
            }
        }

        acquired.await()

        val result = shortWaitElection.runIfLeader(lockName) { "should-not-run" }
        result.shouldBeNull()

        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `runIfLeader - action이 예외를 던지면 예외가 호출자에게 전파된다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)
        val lockName = randomName()

        val result = runCatching {
            election.runIfLeader(lockName) { throw LeaderElectionException("테스트 예외") }
        }

        result.isFailure.shouldBeTrue()
    }

    @Test
    fun `runIfLeader - action 예외 발생 후 lock이 해제되어 다음 호출이 성공한다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)
        val lockName = randomName()

        runCatching {
            election.runIfLeader(lockName) { throw LeaderElectionException("실패") }
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 코루틴 취소 시 락 문서가 삭제된다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)
        val lockName = randomName()

        val acquired = CompletableDeferred<Unit>()

        val job = launch {
            election.runIfLeader(lockName) {
                acquired.complete(Unit)
                delay(Long.MAX_VALUE.milliseconds)
            }
        }

        acquired.await()
        job.cancel()
        job.join()

        val doc = lockCollection.find(Filters.eq("_id", lockName)).first()
        doc.shouldBeNull()
    }

    @Test
    fun `runIfLeader - 반복 실행 시 매번 성공한다`() = runSuspendIO {
        val election = MongoSuspendLeaderElection(coroutineLockCollection)
        val lockName = randomName()

        repeat(5) { i ->
            val result = election.runIfLeader(lockName) { "round-$i" }
            result shouldBeEqualTo "round-$i"
        }
    }

    @Test
    fun `ensureIndexes - resetEnsuredFor 후 재호출 시 에러 없이 완료된다 (suspend)`() = runSuspendIO {
        val namespace = coroutineLockCollection.namespace.fullName
        io.bluetape4k.leader.mongodb.lock.MongoSuspendLock.resetEnsuredFor(namespace)

        io.bluetape4k.leader.mongodb.lock.MongoSuspendLock.ensureIndexes(coroutineLockCollection)
    }
}
