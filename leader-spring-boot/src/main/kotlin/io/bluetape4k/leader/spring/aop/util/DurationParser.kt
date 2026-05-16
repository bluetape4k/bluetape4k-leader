package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.time.Duration

/**
 * Helper for parsing annotation `waitTime` / `leaseTime` strings.
 *
 * Borrowed from ShedLock `StringToDurationConverter.java:54-97` pattern — supports both formats:
 * - **ISO-8601**: `"PT10S"`, `"PT1H"`, `"PT500MS"` → [Duration.parse]
 * - **simple**: `"10s"`, `"5m"`, `"1h"`, `"500ms"` → regex + unit mapping
 *
 * The default [parse] rejects negative or zero values. For values that allow zero such as `minLeaseTime`,
 * use [parseNonNegativeOrDefault].
 *
 * ## Usage example
 * ```kotlin
 * DurationParser.parse("10s")     // PT10S
 * DurationParser.parse("PT1H")    // PT1H
 * DurationParser.parse("500ms")   // PT0.5S
 * DurationParser.parseOrDefault("", Duration.ofSeconds(5))  // PT5S (fallback)
 * ```
 */
object DurationParser {

    private val SIMPLE_PATTERN = Regex("^(\\d+)\\s*(ms|s|m|h|d)$", RegexOption.IGNORE_CASE)

    /**
     * Parses [text] into a [Duration]. Accepts ISO-8601 (`PT10S`) or simple (`10s`) format.
     *
     * @throws IllegalArgumentException if [text] is blank, does not match a supported format, or is negative/zero
     */
    fun parse(text: String): Duration {
        text.requireNotBlank("text")
        val trimmed = text.trim()

        val duration = if (trimmed.startsWith("PT", ignoreCase = true) || trimmed.startsWith("P", ignoreCase = true)) {
            runCatching { Duration.parse(trimmed) }
                .getOrElse { throw IllegalArgumentException("Invalid ISO-8601 duration: '$text'", it) }
        } else {
            val match = SIMPLE_PATTERN.matchEntire(trimmed)
                ?: throw IllegalArgumentException(
                    "Invalid duration format: '$text'. Expected ISO-8601 (PT10S) or simple (10s/5m/1h/500ms)"
                )
            val (valueStr, unit) = match.destructured
            val value = valueStr.toLong()
            when (unit.lowercase()) {
                "ms" -> Duration.ofMillis(value)
                "s" -> Duration.ofSeconds(value)
                "m" -> Duration.ofMinutes(value)
                "h" -> Duration.ofHours(value)
                "d" -> Duration.ofDays(value)
                else -> throw IllegalArgumentException("Unknown duration unit: '$unit' in '$text'")
            }
        }

        duration.requireGt(Duration.ZERO, "duration")
        return duration
    }

    /**
     * Returns [default] if [text] is blank; otherwise returns the result of [parse].
     */
    fun parseOrDefault(text: String, default: Duration): Duration =
        if (text.isBlank()) default else parse(text)

    /**
     * Returns [default] if [text] is blank; otherwise parses [text] as a non-negative [Duration].
     */
    fun parseNonNegativeOrDefault(text: String, default: Duration): Duration {
        if (text.isBlank()) return default
        val duration = runCatching { parse(text) }
            .getOrElse { error ->
                val trimmed = text.trim()
                if (trimmed == "0" || trimmed.equals("PT0S", ignoreCase = true)) {
                    return Duration.ZERO
                }
                throw error
            }
        return duration
    }
}
