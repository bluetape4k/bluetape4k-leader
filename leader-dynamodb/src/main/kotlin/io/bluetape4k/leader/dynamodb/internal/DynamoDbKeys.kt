package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.validateLockName

internal object DynamoDbKeys {
    private const val SlotMarker = "#slot-"
    private val TableNamePattern = Regex("^[a-zA-Z0-9_.-]{3,255}$")
    private val KeyPrefixPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9_:-]{0,127}$")

    fun validateTableName(tableName: String) {
        require(TableNamePattern.matches(tableName)) {
            "tableName must be 3-255 chars and contain only [a-zA-Z0-9_.-]: $tableName"
        }
    }

    fun validateKeyPrefix(keyPrefix: String) {
        require(KeyPrefixPattern.matches(keyPrefix)) {
            "keyPrefix must be 1-128 chars, start with alphanumeric, and contain only [a-zA-Z0-9_:-]: $keyPrefix"
        }
        require(!keyPrefix.contains(SlotMarker)) { "keyPrefix must not contain '$SlotMarker': $keyPrefix" }
    }

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
