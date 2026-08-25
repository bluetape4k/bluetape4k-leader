package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.contract.AbstractLockNameConformanceTest
import io.bluetape4k.leader.strategy.CandidateInfo

/**
 * Lettuce blocking/suspend strategic registry가 실제 Redis key 생성 전에
 * core lock-name conformance fixture를 적용하는지 검증합니다.
 */
class LettuceBlockingLockNameConformanceTest : AbstractLockNameConformanceTest() {

    private val node = LettuceStrategicLeaderElector(AbstractLettuceLeaderTest.connection, "contract-blocking")

    override fun validateLockName(lockName: String) {
        val candidate = CandidateInfo("contract-${System.nanoTime()}")
        var registered = false
        try {
            node.registerCandidate(lockName, candidate)
            registered = true
        } finally {
            if (registered) {
                node.unregisterCandidate(lockName, candidate.nodeId)
            }
        }
    }
}

class LettuceSuspendLockNameConformanceTest : AbstractLockNameConformanceTest() {

    private val node = LettuceStrategicSuspendLeaderElector(AbstractLettuceLeaderTest.connection, "contract-suspend")

    override fun validateLockName(lockName: String) {
        runSuspendIO {
            val candidate = CandidateInfo("contract-${System.nanoTime()}")
            var registered = false
            try {
                node.registerCandidate(lockName, candidate)
                registered = true
            } finally {
                if (registered) {
                    node.unregisterCandidate(lockName, candidate.nodeId)
                }
            }
        }
    }
}
