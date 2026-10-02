package io.bluetape4k.leader.mongodb

import io.bluetape4k.leader.validateLockName

@Deprecated("use extension methods")
internal fun validateMongoLockName(lockName: String) {
    lockName.validateLockName()
    require(!lockName.contains(":slot:")) { "lockName must not contain ':slot:': $lockName" }
}

fun String.validateMonoLockName(parameterName: String = "lockName") {
    validateLockName()
    require(!contains(":slot:")) { "lockName must not contain ':slot:': $this" }
}
