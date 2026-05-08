package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockNameValidatorTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a",
            "A",
            "0",
            "job",
            "daily-report",
            "batch_job",
            "leader:election",
            "job-123",
            "JOB_NAME",
        ]
    )
    fun `유효한 lockName은 검증을 통과한다`(lockName: String) {
        validateLockName(lockName)
    }

    @Test
    fun `255자 lockName은 통과한다`() {
        val lockName = "a" + "b".repeat(254)
        lockName.length shouldBeEqualTo 255
        validateLockName(lockName)
    }

    @Test
    fun `빈 문자열은 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> { validateLockName("") }
    }

    @Test
    fun `공백 문자열은 예외가 발생한다`() {
        assertFailsWith<IllegalArgumentException> { validateLockName("   ") }
    }

    @Test
    fun `256자 lockName은 예외가 발생한다`() {
        val tooLong = "a" + "b".repeat(255)
        tooLong.length shouldBeEqualTo 256
        assertFailsWith<IllegalArgumentException> { validateLockName(tooLong) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            ".leading-dot",
            "has.dot",
            "has space",
            "has@at",
            "has#hash",
            "-leading-hyphen",
            ":leading-colon",
            "_leading-underscore",
        ]
    )
    fun `허용되지 않는 문자나 패턴은 예외가 발생한다`(lockName: String) {
        assertFailsWith<IllegalArgumentException> { validateLockName(lockName) }
    }

    @Test
    fun `콜론 포함 lockName은 통과한다 - 백엔드별 추가 검증은 각 백엔드 담당`() {
        validateLockName("leader:election:slot")
        validateLockName("group:job:0")
    }
}
