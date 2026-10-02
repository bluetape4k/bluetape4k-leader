package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.validateLockName

/**
 * `validateExposedLockName` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
@Deprecated("use String.validateExposedLockName() instead")
@JvmName("validateExposedLockNameMethod")
internal fun validateExposedLockName(lockName: String) {
    lockName.validateLockName()
}

/**
 * `validateExposedLockName` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
internal fun String.validateExposedLockName() {
    this.validateLockName()
}
