package io.bluetape4k.leader.exposed.history

/**
 * Lightweight JSON codec for `Map<String, String>` metadata stored in the
 * `bluetape4k_leader_lock_history.metadata` column.
 *
 * ## Behavior / Contract
 * - [encode] returns `null` for an empty map (avoids storing empty `{}` in the DB).
 * - [decode] returns an empty map for null or blank input.
 * - Keys and values must not contain raw control characters; callers are expected to
 *   have applied `sanitizeForLog()` before encoding.
 * - This codec does **not** depend on Jackson or kotlinx.serialization to avoid
 *   adding a heavy runtime dependency to `leader-exposed-core`.
 *
 * ## Example
 * ```kotlin
 * val json  = MetadataJsonCodec.encode(mapOf("env" to "prod", "region" to "us-east-1"))
 * // json == "{\"env\":\"prod\",\"region\":\"us-east-1\"}"
 * val map   = MetadataJsonCodec.decode(json)
 * // map  == mapOf("env" to "prod", "region" to "us-east-1")
 * ```
 */
object MetadataJsonCodec {

    /**
     * Encodes [map] to a compact JSON object string.
     * Returns `null` when [map] is empty.
     */
    fun encode(map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        return buildString {
            append('{')
            var first = true
            for ((k, v) in map) {
                if (!first) append(',')
                first = false
                appendJsonString(k)
                append(':')
                appendJsonString(v)
            }
            append('}')
        }
    }

    /**
     * Decodes a JSON object string previously produced by [encode].
     * Returns an empty map for null or blank input.
     */
    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val trimmed = json.trim()
        if (trimmed == "{}") return emptyMap()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return emptyMap()

        val result = LinkedHashMap<String, String>()
        val inner = trimmed.substring(1, trimmed.length - 1)
        var pos = 0
        while (pos < inner.length) {
            pos = skipWhitespace(inner, pos)
            if (pos >= inner.length) break
            val (key, afterKey) = readString(inner, pos) ?: break
            pos = skipWhitespace(inner, afterKey)
            if (pos >= inner.length || inner[pos] != ':') break
            pos = skipWhitespace(inner, pos + 1)
            val (value, afterValue) = readString(inner, pos) ?: break
            pos = skipWhitespace(inner, afterValue)
            result[key] = value
            if (pos < inner.length && inner[pos] == ',') pos++
        }
        return result
    }

    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"'  -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun skipWhitespace(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun readString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"'  -> { sb.append('"');  i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n'  -> { sb.append('\n'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(s[i + 1]); i += 2 }
                }
            } else if (ch == '"') {
                return Pair(sb.toString(), i + 1)
            } else {
                sb.append(ch)
                i++
            }
        }
        return null
    }
}
