package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.contract.AbstractLockNameConformanceTest
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient

private val invalidLockNames = listOf(
    "",
    "   ",
    "a" + "b".repeat(255),
    ".leading-dot",
    "has.dot",
    "has space",
    "has@at",
    "has#hash",
    "-leading-hyphen",
    ":leading-colon",
    "_leading-underscore",
)

/** Redisson blocking strategic electors가 실제 key 생성 전에 이름을 검증하는지 확인합니다. */
class RedissonBlockingLockNameConformanceTest : AbstractLockNameConformanceTest() {

    private val single = RedissonStrategicLeaderElector(
        AbstractRedissonLeaderTest.redissonClient,
        "contract-blocking-single",
    )
    private val group = RedissonStrategicLeaderGroupElector(
        AbstractRedissonLeaderTest.redissonClient,
        "contract-blocking-group",
    )

    override fun validateLockName(lockName: String) {
        val candidate = CandidateInfo("contract-${System.nanoTime()}")
        var singleRegistered = false
        var groupRegistered = false
        try {
            single.registerCandidate(lockName, candidate)
            singleRegistered = true
            group.registerCandidate(lockName, candidate)
            groupRegistered = true
        } finally {
            if (singleRegistered) {
                single.unregisterCandidate(lockName, candidate.nodeId)
            }
            if (groupRegistered) {
                group.unregisterCandidate(lockName, candidate.nodeId)
            }
        }
    }

    @Test
    fun `단일 및 그룹 runIfLeader는 잘못된 lockName을 즉시 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            single.runIfLeader("", FifoElectionStrategy) { error("실행되면 안 됨") }
        }
        assertFailsWith<IllegalArgumentException> {
            group.runIfLeader(" ", FifoGroupElectionStrategy) { error("실행되면 안 됨") }
        }
    }

    @Test
    fun `단일 및 그룹 후보 등록은 전체 invalid corpus를 거부한다`() {
        invalidLockNames.forEach { lockName ->
            val candidate = CandidateInfo("contract-${System.nanoTime()}")
            assertFailsWith<IllegalArgumentException> {
                single.registerCandidate(lockName, candidate)
            }
            assertFailsWith<IllegalArgumentException> {
                group.registerCandidate(lockName, candidate)
            }
        }
    }
}

/** Redisson suspend strategic electors가 실제 key 생성 전에 이름을 검증하는지 확인합니다. */
class RedissonSuspendLockNameConformanceTest : AbstractLockNameConformanceTest() {

    private val single = RedissonStrategicSuspendLeaderElector(
        AbstractRedissonLeaderTest.redissonClient,
        "contract-suspend-single",
    )
    private val group = RedissonStrategicSuspendLeaderGroupElector(
        AbstractRedissonLeaderTest.redissonClient,
        "contract-suspend-group",
    )

    override fun validateLockName(lockName: String) = runSuspendIO {
        val candidate = CandidateInfo("contract-${System.nanoTime()}")
        var singleRegistered = false
        var groupRegistered = false
        try {
            single.registerCandidate(lockName, candidate)
            singleRegistered = true
            group.registerCandidate(lockName, candidate)
            groupRegistered = true
        } finally {
            if (singleRegistered) {
                single.unregisterCandidate(lockName, candidate.nodeId)
            }
            if (groupRegistered) {
                group.unregisterCandidate(lockName, candidate.nodeId)
            }
        }
    }

    @Test
    fun `단일 및 그룹 suspend runIfLeader는 잘못된 lockName을 즉시 거부한다`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            single.runIfLeader("", FifoElectionStrategy) { error("실행되면 안 됨") }
        }
        assertFailsWith<IllegalArgumentException> {
            group.runIfLeader(" ", FifoGroupElectionStrategy) { error("실행되면 안 됨") }
        }
    }

    @Test
    fun `단일 및 그룹 suspend 후보 등록은 전체 invalid corpus를 거부한다`() = runSuspendIO {
        invalidLockNames.forEach { lockName ->
            val candidate = CandidateInfo("contract-${System.nanoTime()}")
            assertFailsWith<IllegalArgumentException> {
                single.registerCandidate(lockName, candidate)
            }
            assertFailsWith<IllegalArgumentException> {
                group.registerCandidate(lockName, candidate)
            }
        }
    }
}

class RedissonLockNameValidationOrderingTest {

    @Test
    fun `blocking strategic invalid lockName은 Redisson map cache 전에 거부된다`() {
        val client = mockk<RedissonClient>(relaxed = true)
        val single = RedissonStrategicLeaderElector(client)
        val group = RedissonStrategicLeaderGroupElector(client)

        assertFailsWith<IllegalArgumentException> {
            single.runIfLeader("", FifoElectionStrategy) { error("실행되면 안 됨") }
        }
        assertFailsWith<IllegalArgumentException> {
            group.runIfLeader(" ", FifoGroupElectionStrategy) { error("실행되면 안 됨") }
        }

        verify(exactly = 0) { client.getMapCache<String, CandidateInfo>(any<String>()) }
    }

    @Test
    fun `suspend strategic invalid lockName은 Redisson map cache 전에 거부된다`() = runSuspendIO {
        val client = mockk<RedissonClient>(relaxed = true)
        val single = RedissonStrategicSuspendLeaderElector(client)
        val group = RedissonStrategicSuspendLeaderGroupElector(client)

        assertFailsWith<IllegalArgumentException> {
            single.runIfLeader("", FifoElectionStrategy) { error("실행되면 안 됨") }
        }
        assertFailsWith<IllegalArgumentException> {
            group.runIfLeader(" ", FifoGroupElectionStrategy) { error("실행되면 안 됨") }
        }

        verify(exactly = 0) { client.getMapCache<String, CandidateInfo>(any<String>()) }
    }
}
