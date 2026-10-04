package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.LeaderLease
import java.time.Instant

/**
 * `ConsulOwnerPayload`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 data class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property ownerToken Consul backend 계약에서 `ownerToken` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property auditLeaderId Consul backend 계약에서 `auditLeaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property nodeId Consul backend 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property electedAt Consul backend 계약에서 `electedAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseUntil Consul backend 계약에서 `leaseUntil` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
