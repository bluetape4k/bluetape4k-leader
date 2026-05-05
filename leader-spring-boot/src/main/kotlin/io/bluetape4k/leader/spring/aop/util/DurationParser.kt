package io.bluetape4k.leader.spring.aop.util

import java.time.Duration

/**
 * 어노테이션 `waitTime` / `leaseTime` 문자열 파싱 헬퍼.
 *
 * ShedLock `StringToDurationConverter.java:54-97` 패턴 차용 — 두 형식 모두 지원:
 * - **ISO-8601**: `"PT10S"`, `"PT1H"`, `"PT500MS"` → [Duration.parse]
 * - **simple**: `"10s"`, `"5m"`, `"1h"`, `"500ms"` → 정규식 + 단위 매핑
 *
 * 음수/0 거부. 빈 문자열 시 default 폴백 헬퍼 [parseOrDefault] 사용.
 *
 * ## 사용 예
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
     * [text] 를 [Duration] 으로 파싱. ISO-8601 (`PT10S`) 또는 simple (`10s`) 형식.
     *
     * @throws IllegalArgumentException [text] 가 빈 문자열이거나 형식 불일치 또는 음수/0
     */
    fun parse(text: String): Duration {
        require(text.isNotBlank()) { "Duration text must not be blank" }
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

        require(!duration.isNegative && !duration.isZero) {
            "Duration must be positive: '$text' → $duration"
        }
        return duration
    }

    /**
     * [text] 가 빈 문자열이면 [default] 를 반환, 그 외에는 [parse] 결과 반환.
     */
    fun parseOrDefault(text: String, default: Duration): Duration =
        if (text.isBlank()) default else parse(text)
}
