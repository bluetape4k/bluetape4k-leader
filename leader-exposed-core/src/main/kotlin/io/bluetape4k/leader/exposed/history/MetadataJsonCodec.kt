package io.bluetape4k.leader.exposed.history

/**
 * `MetadataJsonCodec`는 leader history metadata를 저장용 JSON 문자열로 인코딩하고 다시 map으로 복원합니다.
 *
 */
object MetadataJsonCodec {

    /**
     * 문자열 map을 JSON object로 인코딩합니다. 빈 map은 저장할 metadata가 없다는 뜻으로 `null`을 반환하며,
     * 큰따옴표·역슬래시·개행·CR·탭은 JSON escape로 기록합니다.
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
     * 이력 metadata JSON object를 문자열 map으로 복원합니다. `null`, 공백, `{}` 또는 object가 아닌 입력은 빈 map으로
     * 처리하고, 파싱할 수 있는 항목까지만 반환합니다.
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
                '"' -> append("\\\"")
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
                    '"' -> {
                        sb.append('"'); i += 2
                    }
                    '\\' -> {
                        sb.append('\\'); i += 2
                    }
                    'n' -> {
                        sb.append('\n'); i += 2
                    }
                    'r' -> {
                        sb.append('\r'); i += 2
                    }
                    't' -> {
                        sb.append('\t'); i += 2
                    }
                    else -> {
                        sb.append(s[i + 1]); i += 2
                    }
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
