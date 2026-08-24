package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [LockNameValidator] — charset 화이트리스트 + max length + lock-name-prefix 검증 (T5.3 + T5.9b).
 */
@Suppress("DEPRECATION")
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
        validator.validate(name)
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
        assertFailsWith<IllegalArgumentException> { validator.validate(name) }
    }

    @Test
    fun `validate - blank 거부`() {
        assertFailsWith<IllegalArgumentException> { validator.validate("") }
        assertFailsWith<IllegalArgumentException> { validator.validate("   ") }
    }

    @Test
    fun `validate - max length 256 초과 시 거부`() {
        val tooLong = "a".repeat(257)
        assertFailsWith<IllegalArgumentException> { validator.validate(tooLong) }
    }

    @Test
    fun `validate - 256자 정확히는 통과`() {
        val exactly256 = "a".repeat(256)
        validator.validate(exactly256)
    }

    @Test
    fun `validateEffectiveName - core 정책과 prefix 결과를 함께 검증`() {
        prefixedValidator.validateEffectiveName("daily-job") shouldBeEqualTo "myapp:daily-job"
        assertFailsWith<IllegalArgumentException> {
            validator.validateEffectiveName("ns.subns.lock")
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validateEffectiveName("a".repeat(256))
        }
        assertFailsWith<IllegalArgumentException> {
            LockNameValidator(prefix = "my.app:").validateEffectiveName("daily-job")
        }
    }

    @Test
    fun `validateEffectiveName - 공개 maxLength는 effective 이름에도 적용된다`() {
        val bounded = LockNameValidator(maxLength = 64)

        bounded.validateEffectiveName("a".repeat(64)) shouldBeEqualTo "a".repeat(64)
        assertFailsWith<IllegalArgumentException> {
            bounded.validateEffectiveName("a".repeat(65))
        }
        assertFailsWith<IllegalArgumentException> {
            LockNameValidator(prefix = "app:", maxLength = 64)
                .validateEffectiveName("a".repeat(61))
        }
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
        assertFailsWith<IllegalArgumentException> { LockNameValidator(maxLength = 0) }
        assertFailsWith<IllegalArgumentException> { LockNameValidator(maxLength = -1) }
    }
}
