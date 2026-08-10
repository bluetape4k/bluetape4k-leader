package io.bluetape4k.leader.exposed.r2dbc.lock

import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import kotlinx.coroutines.flow.firstOrNull
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

internal suspend fun R2dbcTransaction.currentTime(
    useDbTime: Boolean,
    clock: Clock = Clock.systemUTC(),
): Instant =
    if (useDbTime) dbCurrentTimestamp() else Instant.now(clock)

internal fun Any?.toExposedR2dbcInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        is LocalDateTime -> toInstant(ZoneOffset.UTC)
        else -> error("Unsupported CURRENT_TIMESTAMP value: ${this?.javaClass?.name ?: "null"}")
    }

private suspend fun R2dbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { row -> row.get(0).toExposedR2dbcInstant() }
        ?.firstOrNull()
        ?: error("SELECT CURRENT_TIMESTAMP returned no rows")
