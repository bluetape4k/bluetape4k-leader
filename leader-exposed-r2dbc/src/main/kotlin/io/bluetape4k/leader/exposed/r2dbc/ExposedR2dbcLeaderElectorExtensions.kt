package io.bluetape4k.leader.exposed.r2dbc

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * [this] 데이터베이스의 [lockName]을 suspend 방식으로 획득했을 때만 [action]을 실행합니다.
 * 경합으로 획득하지 못하면 null을 반환합니다.
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeader(
    lockName: String,
    options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? =
    ExposedR2DbcSuspendLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * [this] 데이터베이스의 그룹 슬롯을 suspend 방식으로 획득했을 때만 [action]을 실행합니다.
 * 모든 슬롯이 사용 중이면 null을 반환하며 취소 시 획득한 슬롯을 정리합니다.
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeaderGroup(
    lockName: String,
    options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? =
    ExposedR2DbcSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
