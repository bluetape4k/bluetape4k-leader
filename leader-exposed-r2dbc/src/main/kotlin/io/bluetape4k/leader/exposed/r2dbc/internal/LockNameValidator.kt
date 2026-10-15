package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.validateLockName

/** 호환용 JVM facade로, 공통 lock name 검증을 호출합니다. */
@Deprecated("use String.validateExposedR2dbcLockName instead")
@JvmName("validateExposedR2dbcLockNameMethod")
internal fun validateExposedR2dbcLockName(lockName: String) {
    lockName.validateLockName()
}

fun String.validateExposedR2dbcLockName(lockName: String = "lockName") {
    this.validateLockName(lockName)
}
