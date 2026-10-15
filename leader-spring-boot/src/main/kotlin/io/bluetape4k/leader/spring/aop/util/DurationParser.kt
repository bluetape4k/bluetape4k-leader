package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.time.Duration

/** ISO-8601 또는 `500ms`/`10s`/`5m`/`1h`/`1d` 형식의 양수 duration을 파싱합니다. */
object DurationParser {

    private val log = KotlinLogging.logger {}

    private val SIMPLE_PATTERN = Regex("^(\\d+)\\s*(ms|s|m|h|d)$", RegexOption.IGNORE_CASE)

    /** 양수 duration을 파싱하며 형식 오류와 0 이하 값은 `IllegalArgumentException`으로 거부합니다. */
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
        log.debug { "parse `$text` to $duration" }
        return duration
    }

    /** blank 입력이면 `default`를 반환하고, 그 외 입력은 [parse]와 같은 양수 규칙으로 파싱합니다. */
    fun parseOrDefault(text: String, default: Duration): Duration =
        if (text.isBlank()) default else parse(text)

    /** blank 입력은 `default`, 명시적 `0` 또는 `PT0S`는 `Duration.ZERO`, 그 외 입력은 [parse] 결과를 반환합니다. */
    fun parseNonNegativeOrDefault(text: String, default: Duration): Duration {
        if (text.isBlank()) return default
        val duration = runCatching {
            parse(text)
        }.getOrElse { error ->
            val trimmed = text.trim()
            if (trimmed == "0" || trimmed.equals("PT0S", ignoreCase = true)) {
                return Duration.ZERO
            }
            throw error
        }
        log.debug { "parse `$text` to $duration, default: $default" }
        return duration
    }
}
