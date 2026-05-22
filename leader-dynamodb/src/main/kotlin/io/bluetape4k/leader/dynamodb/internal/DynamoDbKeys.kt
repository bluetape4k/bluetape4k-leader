package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.validateLockName

internal object DynamoDbKeys {
    private const val SlotMarker = "#slot-"

    fun validateUserLockName(lockName: String) {
        validateLockName(lockName)
        require(!lockName.contains(SlotMarker)) { "lockName must not contain '$SlotMarker': $lockName" }
    }

    fun single(prefix: String, lockName: String): String =
        "${prefix.trimEnd('/')}/single/$lockName"

    fun groupSlot(prefix: String, lockName: String, slot: Int): String =
        "${prefix.trimEnd('/')}/group/$lockName$SlotMarker$slot"

    fun groupPrefix(prefix: String, lockName: String): String =
        "${prefix.trimEnd('/')}/group/$lockName$SlotMarker"
}
