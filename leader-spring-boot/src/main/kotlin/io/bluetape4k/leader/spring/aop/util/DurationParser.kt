package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotBlank
import java.time.Duration

/**
 * `DurationParser`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
object DurationParser {

    private val SIMPLE_PATTERN = Regex("^(\\d+)\\s*(ms|s|m|h|d)$", RegexOption.IGNORE_CASE)

    /**
     * `parse` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
     * `parseOrDefault` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun parseOrDefault(text: String, default: Duration): Duration =
        if (text.isBlank()) default else parse(text)

    /**
     * `parseNonNegativeOrDefault` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
