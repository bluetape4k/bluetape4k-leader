package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.support.asInstantOrNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal fun JdbcTransaction.currentTime(
    useDbTime: Boolean,
    clock: Clock = Clock.systemUTC(),
): Instant =
    if (useDbTime) dbCurrentTimestamp() else Instant.now(clock)

/** 1.0.0에서 공개된 JVM facade를 유지하는 timestamp 변환 함수입니다. */
internal fun Any?.toExposedJdbcInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
    }

private fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) {
            error("SELECT CURRENT_TIMESTAMP returned no rows")
        }
        resultSet.getObject(1).asInstantOrNull()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")
