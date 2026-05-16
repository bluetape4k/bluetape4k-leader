package io.bluetape4k.leader

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank

/**
 * Common minimum validation for lockName.
 *
 * Allowed characters: `[a-zA-Z0-9_\-:]` (alphanumeric, underscore, hyphen, colon)
 * First character: alphanumeric only
 * Maximum length: 255 characters
 *
 * **2-tier design**: this function validates only the minimum rules common to all backends.
 * Backend-specific rules (e.g., MongoDB `:slot:` prohibition, Lettuce glob metacharacter prohibition)
 * are applied by each backend module's internal validation function after calling this function.
 */
// 첫 문자 1자(영숫자) + 이후 0~254자(영숫자/언더스코어/하이픈/콜론) = 최대 255자
private val LOCK_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-:]{0,254}$")

/**
 * Validates that [lockName] satisfies the common minimum rules.
 * Throws [IllegalArgumentException] if any rule is violated.
 */
fun validateLockName(lockName: String) {
    lockName.requireNotBlank("lockName")
    lockName.length.requireLe(255, "lockName.length")
    require(LOCK_NAME_PATTERN.matches(lockName)) {
        "lockName contains invalid characters. Allowed: [a-zA-Z0-9_\\-:], first char must be alphanumeric, got: $lockName"
    }
}
