package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.support.asInstantOrNull
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import java.time.Clock
import java.time.Instant

internal suspend fun R2dbcTransaction.currentTime(
    useDbTime: Boolean,
    clock: Clock = Clock.systemUTC(),
): Instant =
    if (useDbTime) dbCurrentTimestamp() else Instant.now(clock)

private suspend fun R2dbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { row -> row.get(0).asInstantOrNull() }
        ?.firstOrNull()
        ?: error("SELECT CURRENT_TIMESTAMP returned no rows")
