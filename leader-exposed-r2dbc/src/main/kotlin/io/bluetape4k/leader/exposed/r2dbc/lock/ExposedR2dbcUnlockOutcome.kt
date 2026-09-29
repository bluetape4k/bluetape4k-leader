package io.bluetape4k.leader.exposed.r2dbc.lock

internal enum class ExposedR2dbcUnlockOutcome {
    RELEASED,
    NOT_HELD,
    FAILED,
}
