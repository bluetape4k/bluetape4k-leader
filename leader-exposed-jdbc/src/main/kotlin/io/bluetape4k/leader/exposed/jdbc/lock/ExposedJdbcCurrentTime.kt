package io.bluetape4k.leader.exposed.jdbc.lock

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
        resultSet.getObject(1).toExposedJdbcInstant()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")
