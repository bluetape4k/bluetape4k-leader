package io.bluetape4k.leader.spring.aop.util

/**
 * AOP 어노테이션 lock name 검증 + 자동 prefix 적용.
 *
 * ## [Step 3-P-Sec-2][R-34] charset 화이트리스트
 * `^[A-Za-z0-9_:.\-]+$` — Redis (< 1KB), MongoDB (index 1024) 모두 안전한 문자만 허용.
 * 위반 시 [IllegalArgumentException] — 락-namespace pollution 방어.
 *
 * ## 자동 prefix
 * Aspect 가 SpEL 평가 후 본 validator 호출 직전 [prefix] (default `${spring.application.name}:`)
 * 자동 부착으로 비즈니스 락 namespace 충돌 회피. empty string opt-out 가능.
 *
 * ## 사용 예
 * ```kotlin
 * val validator = LockNameValidator(prefix = "myapp:")
 * validator.validate("daily-job")      // OK
 * validator.applyPrefix("daily-job")   // "myapp:daily-job"
 * validator.validate("invalid name")   // throws (space not allowed)
 * validator.validate("a".repeat(257))  // throws (max 256)
 * ```
 *
 * @property prefix 자동 prefix. empty string 시 opt-out
 * @property maxLength 최대 길이 (prefix 적용 전 기준). default 256
 */
class LockNameValidator(
    val prefix: String = "",
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    init {
        require(maxLength > 0) { "maxLength must be positive: $maxLength" }
    }

    /**
     * [name] 이 charset 화이트리스트 + max length 만족하는지 검증.
     *
     * @throws IllegalArgumentException 위반 시
     */
    fun validate(name: String) {
        require(name.isNotBlank()) { "lock name must not be blank" }
        require(name.length <= maxLength) {
            "lock name must not exceed $maxLength characters: length=${name.length}"
        }
        require(NAME_PATTERN.matches(name)) {
            "lock name contains invalid characters. Allowed: [A-Za-z0-9_:.\\-], got: '$name'"
        }
    }

    /**
     * [prefix] 가 비어 있지 않으면 [name] 앞에 자동 부착, 아니면 [name] 그대로 반환.
     */
    fun applyPrefix(name: String): String =
        if (prefix.isEmpty()) name else "$prefix$name"

    companion object {
        const val DEFAULT_MAX_LENGTH: Int = 256
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
    }
}
