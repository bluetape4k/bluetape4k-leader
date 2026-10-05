package io.bluetape4k.leader

import io.bluetape4k.leader.contract.AbstractLockNameConformanceTest
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class LockNameValidatorTest: AbstractLockNameConformanceTest() {

    companion object: KLogging()

    override fun validateLockName(lockName: String) {
        lockName.validateLockName()
    }

    @Test
    fun `콜론 포함 lockName은 공통 정책을 통과한다`() {
        validateLockName("leader:election:slot")
        validateLockName("group:job:0")
    }

    @Test
    fun `기존 JVM facade 메서드 이름을 유지한다`() {
        val facade = Class.forName("io.bluetape4k.leader.LockNameValidatorKt")
        facade.getDeclaredMethod("validateLockName", String::class.java)
            .returnType shouldBeEqualTo Void.TYPE
    }
}
