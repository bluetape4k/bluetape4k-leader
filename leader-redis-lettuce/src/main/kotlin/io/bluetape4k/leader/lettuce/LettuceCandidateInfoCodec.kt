package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.strategy.CandidateInfo
import java.time.Instant

/**
 * `LettuceCandidateInfoCodec`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
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
     * `String` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
