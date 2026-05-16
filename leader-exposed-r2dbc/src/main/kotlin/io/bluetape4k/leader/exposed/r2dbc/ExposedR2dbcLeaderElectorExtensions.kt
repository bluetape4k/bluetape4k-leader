package io.bluetape4k.leader.exposed.r2dbc

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * Runs a suspend single leader election on this [R2dbcDatabase].
 *
 * Convenience function for `ExposedR2dbcSuspendLeaderElector(this, options).runIfLeader(lockName, action)`.
 * `ensureSchema` is guaranteed via [ExposedR2DbcSuspendLeaderElector.invoke].
 *
 * **Note**: A new [ExposedR2DbcSuspendLeaderElector] instance is created on every call.
 * For frequent repeated calls, create and reuse an instance directly.
 *
 * ```kotlin
 * val report = db.suspendRunIfLeader("daily-report") {
 *     delay(100)
 *     generateReport()
 * } ?: return // skip if not leader
 * ```
 *
 * @param lockName lock name to use for leader election
 * @param options election options; defaults to [ExposedR2dbcLeaderElectionOptions.Default]
 * @param action suspend action to run when leader acquisition succeeds
 * @return result of [action], or `null` if leader acquisition failed
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeader(
    lockName: String,
    options: ExposedR2dbcLeaderElectionOptions = ExposedR2dbcLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = ExposedR2DbcSuspendLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs a suspend group leader election on this [R2dbcDatabase].
 *
 * Convenience function for `ExposedR2dbcSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)`.
 * `ensureSchema` is guaranteed via [ExposedR2DbcSuspendLeaderGroupElector.invoke].
 *
 * **Note**: A new [ExposedR2DbcSuspendLeaderGroupElector] instance is created on every call.
 * For frequent repeated calls, create and reuse an instance directly.
 *
 * ```kotlin
 * val opts = ExposedR2dbcLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 4),
 * )
 * val result = db.suspendRunIfLeaderGroup("worker-pool", opts) {
 *     delay(100)
 *     processChunk()
 * }
 * // Up to 4 nodes run concurrently; returns null when all slots are taken
 * ```
 *
 * @param lockName lock name to use for group leader election
 * @param options group election options; defaults to [ExposedR2dbcLeaderGroupElectionOptions.Default]
 * @param action suspend action to run when leader acquisition succeeds
 * @return result of [action], or `null` if slot acquisition failed
 */
suspend fun <T> R2dbcDatabase.suspendRunIfLeaderGroup(
    lockName: String,
    options: ExposedR2dbcLeaderGroupElectionOptions = ExposedR2dbcLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = ExposedR2DbcSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
