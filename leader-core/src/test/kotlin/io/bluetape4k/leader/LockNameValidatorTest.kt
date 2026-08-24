package io.bluetape4k.leader

import io.bluetape4k.leader.contract.AbstractLockNameConformanceTest
import org.junit.jupiter.api.Test

class LockNameValidatorTest : AbstractLockNameConformanceTest() {

    override fun validateLockName(lockName: String) {
        io.bluetape4k.leader.validateLockName(lockName)
    }

    @Test
    fun `콜론 포함 lockName은 공통 정책을 통과한다`() {
        validateLockName("leader:election:slot")
        validateLockName("group:job:0")
    }
}
