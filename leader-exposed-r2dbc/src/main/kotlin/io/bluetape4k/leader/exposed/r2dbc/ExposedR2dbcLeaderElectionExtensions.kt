package io.bluetape4k.leader.exposed.r2dbc

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * 이 [R2dbcDatabase]에 대한 단일 리더 선출을 suspend 실행합니다.
 *
 * `ExposedR2dbcSuspendLeaderElection(this, options).runIfLeader(lockName, action)`의 편의 함수입니다.
 * [ExposedR2dbcSuspendLeaderElection.invoke]를 통해 ensureSchema가 보장됩니다.
 *
 * **주의**: 호출마다 [ExposedR2dbcSuspendLeaderElection] 인스턴스가 새로 생성됩니다.
 * 반복 호출이 많은 경우 인스턴스를 직접 생성하여 재사용하세요.
 *
 * ```kotlin
 * val report = db.suspendRunIfLeader("daily-report") {
 *     delay(100)
 *     generateReport()
 * } ?: return // 리더가 아니면 건너뜀
 * ```
 *
 * @param lockName 리더 선출에 사용할 락 이름
 * @param options 선출 옵션. 기본값 [ExposedR2dbcLeaderElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 suspend 작업
 * @return [action] 실행 결과, 리더 획득 실패 시 `null`
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeader(
    lockName: String,
    options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = ExposedR2dbcSuspendLeaderElection(this, options).runIfLeader(lockName, action)

/**
 * 이 [R2dbcDatabase]에 대한 그룹 리더 선출을 suspend 실행합니다.
 *
 * `ExposedR2dbcSuspendLeaderGroupElection(this, options).runIfLeader(lockName, action)`의 편의 함수입니다.
 * [ExposedR2dbcSuspendLeaderGroupElection.invoke]를 통해 ensureSchema가 보장됩니다.
 *
 * **주의**: 호출마다 [ExposedR2dbcSuspendLeaderGroupElection] 인스턴스가 새로 생성됩니다.
 * 반복 호출이 많은 경우 인스턴스를 직접 생성하여 재사용하세요.
 *
 * ```kotlin
 * val opts = ExposedR2dbcLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 4),
 * )
 * val result = db.suspendRunIfLeaderGroup("worker-pool", opts) {
 *     delay(100)
 *     processChunk()
 * }
 * // 최대 4개 노드 동시 실행, 슬롯 만석 시 null
 * ```
 *
 * @param lockName 리더 그룹 선출에 사용할 락 이름
 * @param options 그룹 선출 옵션. 기본값 [ExposedR2dbcLeaderGroupElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 suspend 작업
 * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeaderGroup(
    lockName: String,
    options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = ExposedR2dbcSuspendLeaderGroupElection(this, options).runIfLeader(lockName, action)
