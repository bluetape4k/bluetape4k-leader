package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotContains

internal object DynamoDbKeys {
    private const val SlotMarker = "#slot-"
    private val TableNamePattern = Regex("^[a-zA-Z0-9_.-]{3,255}$")
    private val KeyPrefixPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9_:-]{0,127}$")

    fun validateTableName(tableName: String) {
        tableName.requireMatches(TableNamePattern, "tableName")
    }

    fun validateKeyPrefix(keyPrefix: String) {
        keyPrefix.requireMatches(KeyPrefixPattern, "keyPrefix")
        keyPrefix.requireNotContains(SlotMarker, "keyPrefix")
    }

    fun validateUserLockName(lockName: String) {
        lockName.validateLockName()
        lockName.requireNotContains(SlotMarker, "lockName")
    }

    fun single(prefix: String, lockName: String): String =
        "${prefix.trimEnd('/')}/single/$lockName"

    fun groupSlot(prefix: String, lockName: String, slot: Int): String =
        "${prefix.trimEnd('/')}/group/$lockName$SlotMarker$slot"

    fun groupPrefix(prefix: String, lockName: String): String =
        "${prefix.trimEnd('/')}/group/$lockName$SlotMarker"
}
