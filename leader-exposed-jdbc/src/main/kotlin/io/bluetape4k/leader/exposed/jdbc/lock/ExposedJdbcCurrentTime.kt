package io.bluetape4k.leader.exposed.jdbc.lock

import io.bluetape4k.support.asInstantOrNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Clock
import java.time.Instant

internal fun JdbcTransaction.currentTime(
    useDbTime: Boolean,
    clock: Clock = Clock.systemUTC(),
): Instant =
    if (useDbTime) dbCurrentTimestamp() else Instant.now(clock)

internal fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) {
            error("SELECT CURRENT_TIMESTAMP returned no rows")
        }
        resultSet.getObject(1).asInstantOrNull()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")
