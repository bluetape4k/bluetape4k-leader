package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber

/**
 * Lock name validation and automatic prefix application for AOP annotations.
 *
 * ## [Step 3-P-Sec-2][R-34] Charset whitelist
 * `^[A-Za-z0-9_:.\-]+$` — only characters safe for both Redis (< 1KB) and MongoDB (index 1024) are allowed.
 * Violation throws [IllegalArgumentException] — guards against lock-namespace pollution.
 *
 * ## Automatic prefix
 * The Aspect attaches [prefix] (default `${spring.application.name}:`) automatically just before calling
 * this validator after SpEL evaluation, preventing business lock namespace collisions.
 * Pass an empty string to opt out.
 *
 * ## Usage example
 * ```kotlin
 * val validator = LockNameValidator(prefix = "myapp:")
 * validator.validate("daily-job")      // OK
 * validator.applyPrefix("daily-job")   // "myapp:daily-job"
 * validator.validate("invalid name")   // throws (space not allowed)
 * validator.validate("a".repeat(257))  // throws (max 256)
 * ```
 *
 * @property prefix Automatic prefix; pass empty string to opt out
 * @property maxLength Maximum length before prefix is applied. Default 256
 */
class LockNameValidator(
    val prefix: String = "",
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    init {
        maxLength.requirePositiveNumber("maxLength")
    }

    /**
     * Validates that [name] satisfies the charset whitelist and max length constraints.
     *
     * @throws IllegalArgumentException if any constraint is violated
     */
    fun validate(name: String) {
        name.requireNotBlank("name")
        name.length.requireLe(maxLength, "name.length")
        require(NAME_PATTERN.matches(name)) {
            "lock name contains invalid characters. Allowed: [A-Za-z0-9_:.\\-], got: '$name'"
        }
    }

    /**
     * Prepends [prefix] to [name] if [prefix] is non-empty; otherwise returns [name] as-is.
     */
    fun applyPrefix(name: String): String =
        if (prefix.isEmpty()) name else "$prefix$name"

    companion object {
        const val DEFAULT_MAX_LENGTH: Int = 256
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
    }
}
