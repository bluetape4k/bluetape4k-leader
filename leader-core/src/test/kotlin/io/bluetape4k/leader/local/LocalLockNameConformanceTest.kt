package io.bluetape4k.leader.local

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.contract.AbstractLockNameConformanceTest
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import org.junit.jupiter.api.Test

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

/** Local blocking strategic electors가 공통 lock-name 계약을 따르는지 검증합니다. */
class LocalBlockingLockNameConformanceTest : AbstractLockNameConformanceTest() {

    private val single = LocalStrategicLeaderElector("contract-blocking-single")
    private val group = LocalStrategicLeaderGroupElector("contract-blocking-group")

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

/** Local suspend strategic electors가 공통 lock-name 계약을 따르는지 검증합니다. */
class LocalSuspendLockNameConformanceTest : AbstractLockNameConformanceTest() {

    private val single = LocalStrategicSuspendLeaderElector("contract-suspend-single")
    private val group = LocalStrategicSuspendLeaderGroupElector("contract-suspend-group")

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
