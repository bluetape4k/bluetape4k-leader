package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.validateLockName

@Deprecated("use extension methods")
internal fun validateMongoLockName(lockName: String) {
    lockName.validateLockName()
    require(!lockName.contains(":slot:")) { "lockName must not contain ':slot:': $lockName" }
}

fun String.validateMongoLockName(parameterName: String = "lockName") {
    validateLockName()
    require(!contains(":slot:")) { "$parameterName must not contain ':slot:': $this" }
}

/** 1.x에서 공개한 Mongo lock-name 검증 확장 함수의 호환 bridge입니다. */
@Deprecated("use String.validateMongoLockName() instead")
@Suppress("DEPRECATION")
fun String.validateMonoLockName(parameterName: String = "lockName") {
    validateMongoLockName(parameterName)
}
