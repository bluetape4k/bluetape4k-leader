package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.LeaderLease
import java.time.Instant

/**
 * `ConsulOwnerPayload`는 Consul KV value에 저장할 owner, audit, node, 선출/만료 시각을 직렬화합니다.
 *
 * @property ownerToken KV acquire 소유자 검증용 token입니다. JSON payload에는 저장되지만 로그·문서의 식별자로 사용하지 않습니다.
 * @property auditLeaderId audit와 [LeaderLease]에 노출할 leader ID입니다.
 * @property nodeId 현재 작업 노드 식별자이며 [LeaderLease]에 전달됩니다.
 * @property electedAt ownership 획득 시각입니다.
 * @property leaseUntil Consul session/lease가 유효할 것으로 예상하는 만료 시각입니다.
 */
internal data class ConsulOwnerPayload(
    val ownerToken: String,
    val auditLeaderId: String,
    val nodeId: String,
    val electedAt: Instant,
    val leaseUntil: Instant,
) {

    fun toJson(): String =
        buildString {
            append('{')
            appendJsonField("ownerToken", ownerToken)
            append(',')
            appendJsonField("auditLeaderId", auditLeaderId)
            append(',')
            appendJsonField("nodeId", nodeId)
            append(',')
            appendJsonField("electedAt", electedAt.toString())
            append(',')
            appendJsonField("leaseUntil", leaseUntil.toString())
            append('}')
        }

    fun toLeaderLease(slot: Int? = null): LeaderLease =
        LeaderLease(
            auditLeaderId = auditLeaderId,
            electedAt = electedAt,
            leaseUntil = leaseUntil,
            slot = slot,
            nodeId = nodeId,
        )

    companion object {
        fun fromJson(json: String): ConsulOwnerPayload? {
            val fields = parseFlatStringObject(json) ?: return null
            return runCatching {
                ConsulOwnerPayload(
                    ownerToken = fields.getValue("ownerToken"),
                    auditLeaderId = fields.getValue("auditLeaderId"),
                    nodeId = fields.getValue("nodeId"),
                    electedAt = Instant.parse(fields.getValue("electedAt")),
                    leaseUntil = Instant.parse(fields.getValue("leaseUntil")),
                )
            }.getOrNull()
        }
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append('"').append(name).append('"').append(':')
    append('"').append(value.jsonEscape()).append('"')
}

internal fun String.jsonEscape(): String =
    buildString(length) {
        this@jsonEscape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

private fun parseFlatStringObject(json: String): Map<String, String>? {
    val text = json.trim()
    if (!text.startsWith('{') || !text.endsWith('}')) {
        return null
    }

    val result = LinkedHashMap<String, String>()
    var index = 1
    while (index < text.lastIndex) {
        index = text.skipWhitespace(index)
        if (index >= text.lastIndex) break
        val key = text.readJsonString(index) ?: return null
        index = text.skipWhitespace(key.nextIndex)
        if (index >= text.length || text[index] != ':') return null
        index = text.skipWhitespace(index + 1)
        val value = text.readJsonString(index) ?: return null
        result[key.value] = value.value
        index = text.skipWhitespace(value.nextIndex)
        if (index < text.lastIndex) {
            if (text[index] != ',') return null
            index++
        }
    }
    return result
}

private data class JsonString(val value: String, val nextIndex: Int)

private fun String.skipWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index++
    return index
}

private fun String.readJsonString(start: Int): JsonString? {
    if (start >= length || this[start] != '"') return null
    val value = StringBuilder()
    var index = start + 1
    while (index < length) {
        when (val char = this[index]) {
            '"' -> return JsonString(value.toString(), index + 1)
            '\\' -> {
                index++
                if (index >= length) return null
                when (val escaped = this[index]) {
                    '"', '\\', '/' -> value.append(escaped)
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000C')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        if (index + 4 >= length) return null
                        val hex = substring(index + 1, index + 5)
                        val code = hex.toIntOrNull(16) ?: return null
                        value.append(code.toChar())
                        index += 4
                    }
                    else -> return null
                }
            }
            else -> value.append(char)
        }
        index++
    }
    return null
}
