package io.bluetape4k.leader.ktor

internal fun String.jsonValue(): String =
    "\"${jsonEscape()}\""

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
                    if (char.code < CONTROL_CHARACTER_LIMIT) {
                        append("\\u")
                        append(char.code.toString(HEX_RADIX).padStart(UNICODE_HEX_WIDTH, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

private const val CONTROL_CHARACTER_LIMIT: Int = 0x20
private const val HEX_RADIX: Int = 16
private const val UNICODE_HEX_WIDTH: Int = 4
