package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

class ExposedJdbcCurrentTimeTest {

    private val expected = Instant.parse("2026-01-02T03:04:05.006Z")

    @Test
    fun `JDBC current timestamp 반환형을 Instant 로 변환한다`() {
        listOf(
            Timestamp.from(expected),
            expected,
            expected.atOffset(ZoneOffset.ofHours(9)),
            expected.atZone(ZoneOffset.ofHours(-8)),
            LocalDateTime.ofInstant(expected, ZoneOffset.UTC),
        ).forEach { value ->
            value.toExposedJdbcInstant() shouldBeEqualTo expected
        }
    }

    @Test
    fun `지원하지 않는 JDBC current timestamp 반환형은 실패한다`() {
        assertFailsWith<IllegalStateException> {
            Any().toExposedJdbcInstant()
        }
    }
}
