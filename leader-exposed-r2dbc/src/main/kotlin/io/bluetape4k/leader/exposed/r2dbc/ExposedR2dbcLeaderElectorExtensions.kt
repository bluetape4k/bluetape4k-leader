package io.bluetape4k.leader.exposed.r2dbc

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeader(
    lockName: String,
    options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = ExposedR2DbcSuspendLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeaderGroup(
    lockName: String,
    options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = ExposedR2DbcSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
