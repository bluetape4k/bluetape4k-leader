package io.bluetape4k.leader

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank

private const val MANAGEMENT_LOCK_NAME_MAX_BYTES = 128
private val MANAGEMENT_LOCK_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

/** management action selector로 사용할 수 있는 lock 이름인지 예외 없이 확인합니다. */
fun isManagementActionLockName(lockName: String): Boolean =
    lockName.isNotEmpty() &&
        lockName.toByteArray(Charsets.UTF_8).size <= MANAGEMENT_LOCK_NAME_MAX_BYTES &&
        MANAGEMENT_LOCK_NAME_PATTERN.matches(lockName)

/** startup/configuration 경계에서 management action lock 이름을 검증합니다. */
fun requireManagementActionLockName(lockName: String): String {
    val value = lockName.requireNotBlank("lockName")
    value.toByteArray(Charsets.UTF_8).size.requireLe(MANAGEMENT_LOCK_NAME_MAX_BYTES, "lockName.bytes")
    value.requireMatches(MANAGEMENT_LOCK_NAME_PATTERN, "lockName")
    return value
}
