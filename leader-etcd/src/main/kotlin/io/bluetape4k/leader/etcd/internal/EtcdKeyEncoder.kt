package io.bluetape4k.leader.etcd.internal

import java.io.ByteArrayOutputStream

private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

/**
 * Encodes caller-facing lock names into one etcd key segment.
 */
internal object EtcdKeyEncoder {

    fun encodeSegment(value: String): String {
        val bytes = value.encodeToByteArray()
        val result = StringBuilder(bytes.size)

        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            if (isAllowedPathByte(unsigned)) {
                result.append(unsigned.toChar())
            } else {
                result.append('%')
                result.append(HEX_DIGITS[unsigned ushr 4])
                result.append(HEX_DIGITS[unsigned and 0x0F])
            }
        }

        return result.toString()
    }

    fun decodeSegment(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0

        while (index < value.length) {
            val ch = value[index]
            if (ch == '%') {
                require(index + 2 < value.length) { "Malformed percent-encoded segment: $value" }
                val high = decodeHex(value[index + 1])
                val low = decodeHex(value[index + 2])
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                val code = ch.code
                require(isAllowedPathByte(code)) { "Unexpected raw byte in encoded segment: $value" }
                bytes.write(code)
                index++
            }
        }

        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    private fun isAllowedPathByte(value: Int): Boolean =
        value in 'A'.code..'Z'.code ||
                value in 'a'.code..'z'.code ||
                value in '0'.code..'9'.code ||
                value == '.'.code ||
                value == '_'.code ||
                value == '-'.code

    private fun decodeHex(ch: Char): Int =
        when (ch) {
            in '0'..'9' -> ch - '0'
            in 'A'..'F' -> ch - 'A' + 10
            in 'a'..'f' -> ch - 'a' + 10
            else -> throw IllegalArgumentException("Invalid percent-encoded hex digit: $ch")
        }
}
