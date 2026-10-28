package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.asInstant
import io.bluetape4k.support.asInstantOrNull
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class ExposedJdbcCurrentTimeTest {

    companion object: KLogging()

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
            value.asInstant() shouldBeEqualTo expected
        }
    }

    @Test
    fun `지원하지 않는 JDBC current timestamp 반환형은 null 을 반환한다`() {
        Any().asInstantOrNull().shouldBeNull()
    }
}                                                 
