package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

/**
 * [DurationParser] — ISO-8601 + simple 형식, 음수/0 거부, fallback 동작 검증.
 */
class DurationParserTest {

    companion object: KLogging()

    @ParameterizedTest
    @CsvSource(
        "PT10S, PT10S",
        "PT1H, PT1H",
        "PT0.5S, PT0.5S",
        "10s, PT10S",
        "5m, PT5M",
        "1h, PT1H",
        "500ms, PT0.5S",
        "2d, PT48H",
    )
    fun `parse - ISO와 simple 모두 지원`(input: String, expected: String) {
        DurationParser.parse(input) shouldBeEqualTo Duration.parse(expected)
    }

    @ParameterizedTest
    @CsvSource("0s", "PT0S", "PT-1S")
    fun `parse - 음수와 0은 거부한다`(input: String) {
        assertThrows<IllegalArgumentException> { DurationParser.parse(input) }
    }

    @ParameterizedTest
    @CsvSource("'   '", "'invalid'", "'10'", "'10x'", "'PT'")
    fun `parse - 형식 불일치는 거부한다`(input: String) {
        assertThrows<IllegalArgumentException> { DurationParser.parse(input) }
    }

    @Test
    fun `parseOrDefault - 빈 문자열은 default 반환`() {
        DurationParser.parseOrDefault("", Duration.ofSeconds(99)) shouldBeEqualTo Duration.ofSeconds(99)
    }

    @Test
    fun `parseOrDefault - 비어있지 않으면 parse 결과`() {
        DurationParser.parseOrDefault("PT3M", Duration.ofSeconds(99)) shouldBeEqualTo Duration.ofMinutes(3)
    }
}
