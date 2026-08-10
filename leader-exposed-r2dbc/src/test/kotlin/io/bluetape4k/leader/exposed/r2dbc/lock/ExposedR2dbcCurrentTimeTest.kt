package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

class ExposedR2dbcCurrentTimeTest {

    private val expected = Instant.parse("2026-01-02T03:04:05.006Z")

    @Test
    fun `R2DBC current timestamp driver 반환형을 Instant 로 변환한다`() {
        listOf(
            Timestamp.from(expected),
            expected,
            expected.atOffset(ZoneOffset.ofHours(9)),
            expected.atZone(ZoneOffset.ofHours(-8)),
            LocalDateTime.ofInstant(expected, ZoneOffset.UTC),
        ).forEach { value ->
            value.toExposedR2dbcInstant() shouldBeEqualTo expected
        }
    }

    @Test
    fun `지원하지 않는 R2DBC current timestamp 반환형은 실패한다`() {
        assertFailsWith<IllegalStateException> {
            Any().toExposedR2dbcInstant()
        }
    }
}
