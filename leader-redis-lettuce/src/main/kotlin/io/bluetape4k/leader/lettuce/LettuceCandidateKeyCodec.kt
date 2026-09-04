package io.bluetape4k.leader.lettuce

import io.lettuce.core.RedisCommandExecutionException
import java.nio.charset.StandardCharsets

/**
 * Lettuce strategic 후보 Redis key를 논리적 구성 요소별로 인코딩합니다.
 *
 * `lockName`과 `nodeId`를 raw delimiter로 연결하면 `:`를 포함하는 허용 값이
 * 서로 다른 후보를 같은 key로 만들 수 있습니다. 새 key는 legacy `:` namespace와
 * 구분되는 version/type marker와 UTF-8 byte length prefix를 사용합니다.
 */
internal object LettuceCandidateKeyCodec {

    private const val V2_VERSION = "v2"
    private const val V3_VERSION = "v3"
    private const val INDEX_TYPE = "i"
    private const val CANDIDATE_TYPE = "c"
    private const val TOMBSTONE_TYPE = "t"
    private const val MIGRATION_TOKEN_TYPE = "m"
    private const val NAMESPACE_SEPARATOR = "|"

    fun indexKey(keyPrefix: String, lockName: String): String =
        "$keyPrefix$NAMESPACE_SEPARATOR$V3_VERSION$NAMESPACE_SEPARATOR$INDEX_TYPE$NAMESPACE_SEPARATOR" +
            hashTag(lockName)

    fun candidateKey(keyPrefix: String, lockName: String, nodeId: String): String =
        "$keyPrefix$NAMESPACE_SEPARATOR$V3_VERSION$NAMESPACE_SEPARATOR$CANDIDATE_TYPE$NAMESPACE_SEPARATOR" +
            hashTag(lockName) + lengthDelimited(nodeId)

    fun tombstoneKey(keyPrefix: String, lockName: String, nodeId: String): String =
        "$keyPrefix$NAMESPACE_SEPARATOR$V3_VERSION$NAMESPACE_SEPARATOR$TOMBSTONE_TYPE$NAMESPACE_SEPARATOR" +
            hashTag(lockName) + lengthDelimited(nodeId)

    fun migrationTokenKey(keyPrefix: String, lockName: String, nodeId: String): String =
        "$keyPrefix$NAMESPACE_SEPARATOR$V3_VERSION$NAMESPACE_SEPARATOR$MIGRATION_TOKEN_TYPE$NAMESPACE_SEPARATOR" +
            hashTag(lockName) + lengthDelimited(nodeId)

    fun v2IndexKey(keyPrefix: String, lockName: String): String =
        "$keyPrefix$NAMESPACE_SEPARATOR$V2_VERSION$NAMESPACE_SEPARATOR$INDEX_TYPE$NAMESPACE_SEPARATOR" +
            lengthDelimited(lockName)

    fun v2CandidateKey(keyPrefix: String, lockName: String, nodeId: String): String =
        "$keyPrefix$NAMESPACE_SEPARATOR$V2_VERSION$NAMESPACE_SEPARATOR$CANDIDATE_TYPE$NAMESPACE_SEPARATOR" +
            lengthDelimited(lockName) + lengthDelimited(nodeId)

    fun legacyIndexKey(keyPrefix: String, lockName: String): String =
        "$keyPrefix:$lockName"

    fun legacyCandidateKey(keyPrefix: String, lockName: String, nodeId: String): String =
        "${legacyIndexKey(keyPrefix, lockName)}:$nodeId"

    private fun lengthDelimited(value: String): String {
        val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
        return "$byteLength:$value"
    }

    private fun hashTag(lockName: String): String = "{${lengthDelimited(lockName)}}"
}

internal fun RedisCommandExecutionException.isWrongType(): Boolean =
    message?.startsWith("WRONGTYPE") == true
