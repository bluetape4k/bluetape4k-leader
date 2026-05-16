package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import java.time.Instant

/**
 * Internal codec that encodes/decodes [CandidateInfo] to/from a Redis String value.
 *
 * Format: `nodeId|registeredAtMs|lastStartMs|lastCompletionMs|successCount|failureCount|metadata`
 * - Instant?: epochMilli string; empty string when absent
 * - metadata: `key=value` pairs separated by `,`. Keys, values, and nodeId are percent-encoded when they contain `|`, `,`, `=`, or `%`
 */
internal object LettuceCandidateInfoCodec {

    fun encode(info: CandidateInfo): String {
        val meta = info.metadata.entries
            .joinToString(",") { (k, v) -> "${k.esc()}=${v.esc()}" }
        return "${info.nodeId.esc()}|${info.registeredAt.toEpochMilli()}|" +
                "${info.lastStartTime?.toEpochMilli() ?: ""}|" +
                "${info.lastCompletionTime?.toEpochMilli() ?: ""}|" +
                "${info.successCount}|${info.failureCount}|$meta"
    }

    fun decode(encoded: String): CandidateInfo {
        val p = encoded.split("|", limit = 7)
        require(p.size == 7) { "CandidateInfo 인코딩 형식 오류: $encoded" }
        return CandidateInfo(
            nodeId = p[0].unesc(),
            registeredAt = Instant.ofEpochMilli(p[1].toLong()),
            lastStartTime = p[2].toLongOrNull()?.let { Instant.ofEpochMilli(it) },
            lastCompletionTime = p[3].toLongOrNull()?.let { Instant.ofEpochMilli(it) },
            successCount = p[4].toLong(),
            failureCount = p[5].toLong(),
            metadata = decodeMeta(p[6]),
        )
    }

    private fun decodeMeta(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        return s.split(",").associate { kv ->
            val idx = kv.indexOf("=")
            require(idx >= 0) { "metadata 항목에 '=' 가 없습니다: $kv" }
            kv.substring(0, idx).unesc() to kv.substring(idx + 1).unesc()
        }
    }

    private fun String.esc() = replace("%", "%25").replace("|", "%7C").replace(",", "%2C").replace("=", "%3D")

    /**
     * Single-pass percent-decoder. Chained [String.replace] produces incorrect results for nested tokens
     * (e.g., encoded `%7C` = `%257C`), so the string is scanned directly.
     */
    private fun String.unesc(): String {
        if (isEmpty() || !contains('%')) return this
        val sb = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '%' && i + 2 < length) {
                val token = substring(i + 1, i + 3)
                val replaced = when (token) {
                    "25" -> '%'
                    "7C" -> '|'
                    "2C" -> ','
                    "3D" -> '='
                    else -> null
                }
                if (replaced != null) {
                    sb.append(replaced)
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
