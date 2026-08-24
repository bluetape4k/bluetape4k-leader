package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * leader backend가 공유해야 하는 lock-name 검증 경계를 실행하는 conformance fixture입니다.
 *
 * backend 테스트는 [validateLockName]을 실제 key 생성 직전 경로에 연결해 같은
 * 허용 문자, 첫 문자, 최대 길이 계약을 반복 검증할 수 있습니다.
 */
abstract class AbstractLockNameConformanceTest {

    /** 실제 core 또는 backend 진입점의 lock-name 검증을 연결합니다. */
    protected abstract fun validateLockName(lockName: String)

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
        ],
    )
    fun `유효한 lockName은 공통 검증을 통과한다`(lockName: String) {
        validateLockName(lockName)
    }

    @Test
    fun `255자 lockName은 공통 경계를 통과한다`() {
        val lockName = "a" + "b".repeat(254)
        lockName.length shouldBeEqualTo 255
        validateLockName(lockName)
    }

    @Test
    fun `빈 lockName은 공통 검증에서 거부된다`() {
        assertFailsWith<IllegalArgumentException> { validateLockName("") }
        assertFailsWith<IllegalArgumentException> { validateLockName("   ") }
    }

    @Test
    fun `256자 lockName은 공통 검증에서 거부된다`() {
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
        ],
    )
    fun `허용되지 않는 문자와 시작 패턴은 공통 검증에서 거부된다`(lockName: String) {
        assertFailsWith<IllegalArgumentException> { validateLockName(lockName) }
    }
}
