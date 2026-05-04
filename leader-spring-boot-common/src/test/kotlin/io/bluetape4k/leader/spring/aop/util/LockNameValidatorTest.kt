package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [LockNameValidator] — charset 화이트리스트 + max length + lock-name-prefix 검증 (T5.3 + T5.9b).
 */
class LockNameValidatorTest {

    companion object: KLogging()

    private val validator = LockNameValidator()
    private val prefixedValidator = LockNameValidator(prefix = "myapp:")

    @ParameterizedTest
    @ValueSource(
        strings = [
            "daily-job",
            "process-region-1",
            "myapp:tenant-1",
            "ns.subns.lock",
            "abc_xyz_123",
            "A",
        ],
    )
    fun `validate - 화이트리스트 charset 통과`(name: String) {
        assertDoesNotThrow { validator.validate(name) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "with space",
            "slash/path",
            "semi;colon",
            "newline\nname",
            "tab\tname",
            "korean한글",
            "ampersand&",
            "lt<gt>",
        ],
    )
    fun `validate - 화이트리스트 외 문자는 거부`(name: String) {
        assertThrows<IllegalArgumentException> { validator.validate(name) }
    }

    @Test
    fun `validate - blank 거부`() {
        assertThrows<IllegalArgumentException> { validator.validate("") }
        assertThrows<IllegalArgumentException> { validator.validate("   ") }
    }

    @Test
    fun `validate - max length 256 초과 시 거부`() {
        val tooLong = "a".repeat(257)
        assertThrows<IllegalArgumentException> { validator.validate(tooLong) }
    }

    @Test
    fun `validate - 256자 정확히는 통과`() {
        val exactly256 = "a".repeat(256)
        assertDoesNotThrow { validator.validate(exactly256) }
    }

    @Test
    fun `applyPrefix - prefix 빈 문자열 시 그대로 반환`() {
        validator.applyPrefix("daily-job") shouldBeEqualTo "daily-job"
    }

    @Test
    fun `applyPrefix - prefix 비어있지 않으면 부착`() {
        prefixedValidator.applyPrefix("daily-job") shouldBeEqualTo "myapp:daily-job"
    }

    @Test
    fun `init - maxLength 0 또는 음수 거부`() {
        assertThrows<IllegalArgumentException> { LockNameValidator(maxLength = 0) }
        assertThrows<IllegalArgumentException> { LockNameValidator(maxLength = -1) }
    }
}
