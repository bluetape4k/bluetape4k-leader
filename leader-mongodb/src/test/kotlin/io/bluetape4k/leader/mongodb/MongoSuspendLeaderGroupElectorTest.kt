package io.bluetape4k.leader.mongodb

import com.mongodb.client.model.Filters
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionException
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import io.bluetape4k.assertions.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class MongoSuspendLeaderGroupElectorTest: AbstractMongoLeaderTest() {

    companion object: KLogging()

    private suspend fun makeElection(
        maxLeaders: Int = 3,
        waitTime: Duration = 10.seconds,
    ): MongoSuspendLeaderGroupElector =
        MongoSuspendLeaderGroupElector(
            groupCollection = groupLockCollection,
            coroutineGroupCollection = coroutineGroupLockCollection,
            options = MongoLeaderGroupElectionOptions(
                LeaderGroupElectionOptions(
                    maxLeaders = maxLeaders,
                    waitTime = waitTime,
                    leaseTime = 60.seconds,
                )
            )
        )

    @Test
    fun `runIfLeader - 리더로 선출되어 suspend action을 실행하고 결과를 반환한다`() = runSuspendIO {
        val election = makeElection()
        val lockName = randomName()

        val result = election.runIfLeader(lockName) { "success" }

        result shouldBeEqualTo "success"
    }

    @Test
    fun `runIfLeader - 동시 실행 중인 리더 수가 maxLeaders를 초과하지 않는다`() = runSuspendIO {
        val maxLeaders = 3
        val election = makeElection(maxLeaders = maxLeaders)
        val lockName = randomName()
        val currentConcurrent = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)

        val jobs = (1..12).map {
            async {
                election.runIfLeader(lockName) {
                    val current = currentConcurrent.incrementAndGet()
                    peakConcurrent.updateAndGet { max(it, current) }
                    delay(20.milliseconds)
                    currentConcurrent.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        log.debug { "최대 동시 실행 수: ${peakConcurrent.get()} / maxLeaders=$maxLeaders" }
        peakConcurrent.get() shouldBeLessOrEqualTo maxLeaders
    }

    @Test
    fun `runIfLeader - action 예외 발생 후 슬롯이 반환되어 다음 호출이 성공한다`() = runSuspendIO {
        val election = makeElection()
        val lockName = randomName()

        runCatching {
            election.runIfLeader(lockName) { throw LeaderGroupElectionException("슬롯 반환 테스트") }
        }

        val result = election.runIfLeader(lockName) { "복구 성공" }
        result shouldBeEqualTo "복구 성공"
    }

    @Test
    fun `runIfLeader - 콜론슬롯콜론이 포함된 lockName은 IllegalArgumentException을 던진다`() = runSuspendIO {
        val election = makeElection()

        assertFailsWith<IllegalArgumentException> {
            runBlocking { election.runIfLeader("a:slot:b") { "never" } }
        }
    }

    @Test
    fun `activeCount와 availableSlots와 state는 non-suspend 메서드이다`() = runSuspendIO {
        val election = makeElection(maxLeaders = 3)
        val lockName = randomName()

        val count = election.activeCount(lockName)
        val slots = election.availableSlots(lockName)
        val state = election.state(lockName)

        count shouldBeEqualTo 0
        slots shouldBeEqualTo 3
        state.lockName shouldBeEqualTo lockName
        state.maxLeaders shouldBeEqualTo 3
        state.activeCount shouldBeEqualTo 0
        state.isEmpty.shouldBeTrue()
        state.isFull.shouldBeFalse()
    }

    @Test
    fun `runIfLeader - 코루틴 취소 시 슬롯 문서가 삭제된다`() = runSuspendIO {
        val election = makeElection()
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

        val doc = groupLockCollection
            .find(Filters.regex("_id", "^${Regex.escape(lockName)}:slot:"))
            .first()
        doc.shouldBeNull()
    }

    @Test
    fun `runIfLeader - 모든 슬롯 포화 시 짧은 waitTime으로 null을 반환한다`() = runSuspendIO {
        val lockName = randomName()
        val holdingElection = makeElection(maxLeaders = 1, waitTime = 10.seconds)
        val shortWaitElection = makeElection(maxLeaders = 1, waitTime = 50.milliseconds)

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
    fun `runIfLeader - 슬롯 획득 중 코루틴 취소 시 부분 획득된 슬롯이 없다`() = runSuspendIO {
        val lockName = randomName()
        val maxLeaders = 1
        val holder = makeElection(maxLeaders = maxLeaders, waitTime = 30.seconds)
        // 모든 슬롯 점유 중인 election
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holderJob = launch {
            holder.runIfLeader(lockName) {
                acquired.complete(Unit)
                release.await()
            }
        }
        acquired.await()

        // 점유된 슬롯에 진입 시도하다가 취소되는 contender
        val contender = makeElection(maxLeaders = maxLeaders, waitTime = 30.seconds)
        val contenderJob = launch {
            contender.runIfLeader(lockName) { "never" }
        }

        delay(100.milliseconds)
        contenderJob.cancel()
        contenderJob.join()

        // 취소된 contender가 슬롯을 점유하지 않았음을 검증 (holder만 보유)
        val ids = (0 until maxLeaders).map { "$lockName:slot:$it" }
        val activeCount = groupLockCollection.countDocuments(
            com.mongodb.client.model.Filters.`in`("_id", ids)
        )
        activeCount shouldBeEqualTo 1L

        release.complete(Unit)
        holderJob.join()
    }
}
