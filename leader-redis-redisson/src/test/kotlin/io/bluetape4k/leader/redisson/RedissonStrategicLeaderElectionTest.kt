package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.scorers.WeightedScorer
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonStrategicLeaderElectionTest : AbstractRedissonLeaderTest() {

    private lateinit var node1: RedissonStrategicLeaderElection
    private lateinit var node2: RedissonStrategicLeaderElection
    private lateinit var node3: RedissonStrategicLeaderElection

    @BeforeEach
    fun setup() {
        node1 = RedissonStrategicLeaderElection(redissonClient, "node-1")
        node2 = RedissonStrategicLeaderElection(redissonClient, "node-2")
        node3 = RedissonStrategicLeaderElection(redissonClient, "node-3")
    }

    @Test
    fun `FIFO - 가장 먼저 등록된 노드가 action 실행`() {
        val lockName = randomName()
        val t0 = Instant.now()
        val candidates = listOf(
            CandidateInfo("node-1", registeredAt = t0),
            CandidateInfo("node-2", registeredAt = t0.plusMillis(10)),
            CandidateInfo("node-3", registeredAt = t0.plusMillis(20)),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val counter = AtomicInteger(0)
        val r1 = node1.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }
        val r2 = node2.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }
        val r3 = node3.runIfLeader(lockName, FifoElectionStrategy) { counter.incrementAndGet() }

        r1.shouldNotBeNull()
        r2.shouldBeNull()
        r3.shouldBeNull()
        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `후보 없으면 null 반환`() {
        val lockName = randomName()
        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()
    }

    @Test
    fun `TTL 만료 후 후보 자동 제거`() {
        val lockName = randomName()
        val ttl = Duration.ofMillis(300)

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.listCandidates(lockName).size shouldBeEqualTo 1

        Thread.sleep(500)
        node1.listCandidates(lockName).size shouldBeEqualTo 0
    }

    @Test
    fun `updateResult 후 TTL 보존 - 항목이 여전히 만료됨`() {
        val lockName = randomName()
        val ttl = Duration.ofMillis(500)

        node1.registerCandidate(lockName, CandidateInfo("node-1"), ttl)
        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }  // updateResult(SUCCESS) 내부 호출

        // updateResult 후에도 TTL 유지되어야 함
        node1.listCandidates(lockName).size shouldBeEqualTo 1
        Thread.sleep(700)
        node1.listCandidates(lockName).size shouldBeEqualTo 0
    }

    @Test
    fun `unregisterCandidate - 등록 해제 후 목록에서 제거`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.registerCandidate(lockName, CandidateInfo("node-2"))
        node1.unregisterCandidate(lockName, "node-1")

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().nodeId shouldBeEqualTo "node-2"
    }

    @Test
    fun `updateResult - SUCCESS 후 successCount 증가`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `runIfLeader SUCCESS 후 successCount 자동 증가`() {
        val lockName = randomName()
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))

        node1.runIfLeader(lockName, FifoElectionStrategy) { "ok" }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.successCount shouldBeEqualTo 1L
    }

    @Test
    fun `Scored IdleTime - 가장 오래 쉰 노드가 winner로 선출`() {
        val lockName = randomName()
        val now = Instant.now()
        val candidates = listOf(
            CandidateInfo("node-1", lastCompletionTime = now.minusSeconds(10)),
            CandidateInfo("node-2", lastCompletionTime = now.minusSeconds(100)),
            CandidateInfo("node-3", lastCompletionTime = now.minusSeconds(50)),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val strategy = ScoredElectionStrategy(IdleTimeScorer)

        // 모든 노드가 동일한 후보 목록에서 동일한 winner 계산 (결정론적)
        val winner = strategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-2"

        // winner 노드만 action 실행
        val r2 = node2.runIfLeader(lockName, strategy) { "node-2 ran" }
        r2.shouldNotBeNull()

        // updateResult 후 node-2 lastCompletionTime = now → 다음 라운드에서 node-2는 idle=0
        // node-1(idle≈10s)은 여전히 winner가 아님 (node-3 idle≈50s가 다음 winner)
        val r1 = node1.runIfLeader(lockName, strategy) { "node-1 ran" }
        r1.shouldBeNull()
    }

    @Test
    fun `Scored SuccessRate - 성공률 높은 노드 선출`() {
        val lockName = randomName()
        val candidates = listOf(
            CandidateInfo("node-1", successCount = 1, failureCount = 9),   // 10%
            CandidateInfo("node-2", successCount = 9, failureCount = 1),   // 90%
            CandidateInfo("node-3", successCount = 5, failureCount = 5),   // 50%
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        val strategy = ScoredElectionStrategy(SuccessRateScorer)

        // 결정론적 선출 검증
        val winner = strategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-2"

        val counter = AtomicInteger(0)
        node1.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node2.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node3.runIfLeader(lockName, strategy) { counter.incrementAndGet() }

        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `Weighted scorer - 성공률 우선 winner 선출`() {
        val lockName = randomName()
        val candidates = listOf(
            CandidateInfo("node-1", successCount = 10, failureCount = 0),  // 100%
            CandidateInfo("node-2", successCount = 0, failureCount = 10),  // 0%
            CandidateInfo("node-3", successCount = 5, failureCount = 5),   // 50%
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }
        candidates.forEach { node3.registerCandidate(lockName, it) }

        // 성공률 weight 높게 → node-1 선출
        val scorer = WeightedScorer(IdleTimeScorer to 0.1, SuccessRateScorer to 0.9)
        val strategy = ScoredElectionStrategy(scorer)

        val winner = strategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-1"

        val counter = AtomicInteger(0)
        node1.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node2.runIfLeader(lockName, strategy) { counter.incrementAndGet() }
        node3.runIfLeader(lockName, strategy) { counter.incrementAndGet() }

        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `단일 후보는 항상 자신이 선출됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        val result = node1.runIfLeader(lockName, FifoElectionStrategy) { "executed" }
        result.shouldNotBeNull()
        result shouldBeEqualTo "executed"
    }

    @Test
    fun `action 예외 시 failureCount 증가 및 예외 전파`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        assertThrows(RuntimeException::class.java) {
            node1.runIfLeader(lockName, FifoElectionStrategy) {
                throw RuntimeException("intentional error")
            }
        }

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 1L
    }

    @Test
    fun `동일 nodeId 재등록 시 CandidateInfo 갱신됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 3))
        node1.registerCandidate(lockName, CandidateInfo("node-1", successCount = 10))

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().successCount shouldBeEqualTo 10L
    }

    @Test
    fun `서로 다른 lockName은 독립적인 후보 풀`() {
        val lock1 = randomName()
        val lock2 = randomName()

        node1.registerCandidate(lock1, CandidateInfo("node-1"))
        node1.registerCandidate(lock1, CandidateInfo("node-2"))
        node1.registerCandidate(lock2, CandidateInfo("node-3"))

        node1.listCandidates(lock1).size shouldBeEqualTo 2
        node1.listCandidates(lock2).size shouldBeEqualTo 1
    }

    @Test
    fun `updateResult - 존재하지 않는 nodeId는 무시됨`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))

        // 존재하지 않는 nodeId — 예외 없이 무시
        node1.updateResult(lockName, "ghost-node", CandidateResult.SUCCESS)

        val candidates = node1.listCandidates(lockName)
        candidates.size shouldBeEqualTo 1
        candidates.first().successCount shouldBeEqualTo 0L
    }

    @Test
    fun `IdleTime - updateResult 후 winner 변경됨`() {
        val lockName = randomName()
        val now = Instant.now()
        val candidates = listOf(
            CandidateInfo("node-1", lastCompletionTime = now.minusSeconds(100)),
            CandidateInfo("node-2", lastCompletionTime = now.minusSeconds(10)),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }
        candidates.forEach { node2.registerCandidate(lockName, it) }

        val strategy = ScoredElectionStrategy(IdleTimeScorer)

        // 초기: node-1 이 가장 오래 쉬었음 → winner
        val firstWinner = strategy.elect(node1.listCandidates(lockName)).winner
        firstWinner?.nodeId shouldBeEqualTo "node-1"

        // node-1 이 작업 후 lastCompletionTime = now → idle≈0
        node1.updateResult(lockName, "node-1", CandidateResult.SUCCESS)

        // 다음 라운드: node-2 가 더 오래 쉬었음 → winner 변경
        val secondWinner = strategy.elect(node1.listCandidates(lockName)).winner
        secondWinner?.nodeId shouldBeEqualTo "node-2"
    }

    @Test
    fun `FIFO - 동일 registeredAt 시 nodeId 사전순 선출`() {
        val lockName = randomName()
        val t0 = Instant.now()
        // "node-a" < "node-b" < "node-c" 사전순
        val candidates = listOf(
            CandidateInfo("node-c", registeredAt = t0),
            CandidateInfo("node-a", registeredAt = t0),
            CandidateInfo("node-b", registeredAt = t0),
        )
        candidates.forEach { node1.registerCandidate(lockName, it) }

        val winner = FifoElectionStrategy.elect(node1.listCandidates(lockName)).winner
        winner?.nodeId shouldBeEqualTo "node-a"
    }

    @Test
    fun `CandidateInfo - Instant 필드 직렬화 라운드트립`() {
        val lockName = randomName()
        val t0 = Instant.now()
        val original = CandidateInfo(
            nodeId = "node-1",
            registeredAt = t0,
            lastStartTime = t0.plusMillis(100),
            lastCompletionTime = t0.plusMillis(200),
            successCount = 7L,
            failureCount = 3L,
        )
        node1.registerCandidate(lockName, original)

        val retrieved = node1.listCandidates(lockName).first()
        retrieved.nodeId shouldBeEqualTo original.nodeId
        retrieved.successCount shouldBeEqualTo original.successCount
        retrieved.failureCount shouldBeEqualTo original.failureCount
        // Instant 직렬화: 나노초 精度 손실 허용 (밀리초 단위 비교)
        retrieved.registeredAt.toEpochMilli() shouldBeEqualTo original.registeredAt.toEpochMilli()
        retrieved.lastCompletionTime?.toEpochMilli() shouldBeEqualTo original.lastCompletionTime?.toEpochMilli()
    }

    @Test
    fun `updateResult FAILURE - failureCount 증가`() {
        val lockName = randomName()
        node1.registerCandidate(lockName, CandidateInfo("node-1"))
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)
        node1.updateResult(lockName, "node-1", CandidateResult.FAILURE)

        val updated = node1.listCandidates(lockName).first { it.nodeId == "node-1" }
        updated.failureCount shouldBeEqualTo 2L
        updated.successCount shouldBeEqualTo 0L
    }

    @Test
    fun `runIfLeader - winner 아닌 노드는 action 실행 안 함`() {
        val lockName = randomName()
        val t0 = Instant.now()
        node1.registerCandidate(lockName, CandidateInfo("node-1", registeredAt = t0))
        node2.registerCandidate(lockName, CandidateInfo("node-2", registeredAt = t0.plusMillis(10)))

        // node2 는 winner 가 아님 → null
        val result = node2.runIfLeader(lockName, FifoElectionStrategy) { "should-not-run" }
        result.shouldBeNull()

        // node2 의 successCount 변화 없음
        val node2Info = node2.listCandidates(lockName).first { it.nodeId == "node-2" }
        node2Info.successCount shouldBeEqualTo 0L
    }

    @Test
    fun `listCandidates - 빈 lockName 은 빈 목록 반환`() {
        val lockName = randomName()
        node1.listCandidates(lockName).size shouldBeEqualTo 0
    }
}
