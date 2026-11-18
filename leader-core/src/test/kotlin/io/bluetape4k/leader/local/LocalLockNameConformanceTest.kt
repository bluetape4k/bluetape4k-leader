package io.bluetape4k.leader.local

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.fail
import io.bluetape4k.leader.contract.AbstractLockNameConformanceTest
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.testcontainers.utility.Base58

/** Local blocking strategic electors가 공통 lock-name 계약을 따르는지 검증합니다. */
class LocalLockNameConformanceTest: AbstractLockNameConformanceTest() {

    companion object: KLogging() {
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
    }

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
            single.runIfLeader<Any?>("", FifoElectionStrategy) {
                fail("실행되면 안 됨")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            group.runIfLeader<Any?>(" ", FifoGroupElectionStrategy) {
                fail("실행되면 안 됨")
            }
        }
    }

    @Test
    fun `단일 및 그룹 후보 등록은 전체 invalid corpus를 거부한다`() {
        invalidLockNames.forEach { lockName ->
            val candidate = CandidateInfo("contract-${Base58.randomString(8)}")

            assertFailsWith<IllegalArgumentException> {
                single.registerCandidate(lockName, candidate)
            }
            assertFailsWith<IllegalArgumentException> {
                group.registerCandidate(lockName, candidate)
            }
        }
    }
}
