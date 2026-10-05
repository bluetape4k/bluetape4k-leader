package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.validateLockName

/**
 * `validateExposedR2dbcLockName` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
@Deprecated("use String.validateExposedR2dbcLockName instead")
@JvmName("validateExposedR2dbcLockNameMethod")
internal fun validateExposedR2dbcLockName(lockName: String) {
    lockName.validateLockName()
}

fun String.validateExposedR2dbcLockName(lockName: String = "lockName") {
    this.validateLockName(lockName)
}
