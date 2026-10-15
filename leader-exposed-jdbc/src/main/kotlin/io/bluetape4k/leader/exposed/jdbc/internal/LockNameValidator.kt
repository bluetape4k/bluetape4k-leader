package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.validateLockName

/** 호환용 JVM facade로, 공통 lock name 검증을 호출합니다. */
@Deprecated("use String.validateExposedLockName() instead")
@JvmName("validateExposedLockNameMethod")
internal fun validateExposedLockName(lockName: String) {
    lockName.validateLockName()
}

/** 공통 [io.bluetape4k.leader.validateLockName] 규칙으로 JDBC lock name을 검증합니다. */
internal fun String.validateExposedLockName() {
    this.validateLockName()
}
