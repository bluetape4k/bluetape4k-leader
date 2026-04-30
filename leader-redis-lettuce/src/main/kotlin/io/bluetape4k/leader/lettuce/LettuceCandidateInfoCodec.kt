package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import java.time.Instant

/**
 * [CandidateInfo] 를 Redis String 값으로 인코딩/디코딩하는 내부 코덱입니다.
 *
 * 포맷: `nodeId|registeredAtMs|lastStartMs|lastCompletionMs|successCount|failureCount|metadata`
 * - Instant?: epochMilli 문자열, 없으면 빈 문자열
 * - metadata: `key=value` 쌍을 `,` 로 구분. 키/값/nodeId 에 `|`, `,`, `=`, `%` 포함 시 percent-encode
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
            if (idx < 0) kv.unesc() to ""
            else kv.substring(0, idx).unesc() to kv.substring(idx + 1).unesc()
        }
    }

    private fun String.esc() = replace("%", "%25").replace("|", "%7C").replace(",", "%2C").replace("=", "%3D")
    private fun String.unesc() = replace("%7C", "|").replace("%2C", ",").replace("%3D", "=").replace("%25", "%")
}
