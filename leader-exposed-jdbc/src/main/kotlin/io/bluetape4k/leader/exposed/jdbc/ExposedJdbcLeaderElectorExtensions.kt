package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Runs a single leader election on this [Database].
 *
 * Convenience wrapper for `ExposedJdbcLeaderElector(this, options).runIfLeader(lockName, action)`.
 * Schema creation is guaranteed via [ExposedJdbcLeaderElector.invoke].
 *
 * ```kotlin
 * val report = db.runIfLeader("daily-report") { generateReport() }
 *     ?: return // not the leader — skip
 * ```
 *
 * @param lockName lock name used for leader election
 * @param options election options; defaults to [ExposedJdbcLeaderElectionOptions.Default]
 * @param action the work to execute when the leader lock is acquired
 * @return [action] result, or `null` when the leader lock cannot be acquired
 */
fun <T> Database.runIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * Runs a single leader election on this [Database] asynchronously.
 *
 * ```kotlin
 * val future: CompletableFuture<Report?> = db.runAsyncIfLeader("nightly-sync") {
 *     CompletableFuture.supplyAsync { syncData() }
 * }
 * ```
 *
 * @param lockName lock name used for leader election
 * @param executor async [Executor]; defaults to [VirtualThreadExecutor]
 * @param options election options; defaults to [ExposedJdbcLeaderElectionOptions.Default]
 * @param action the async work to execute when the leader lock is acquired
 * @return [CompletableFuture] holding the result, completing with `null` when the leader lock cannot be acquired
 */
fun <T> Database.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)

/**
 * Runs a single leader election on this [Database] asynchronously on a Virtual Thread.
 *
 * ```kotlin
 * val result: String? = db.runVirtualIfLeader("vt-job") { computeHeavy() }
 *     .get(5, TimeUnit.SECONDS)
 * ```
 *
 * @param lockName lock name used for leader election
 * @param options election options; defaults to [ExposedJdbcLeaderElectionOptions.Default]
 * @param action the work to execute when the leader lock is acquired
 * @return [VirtualFuture] holding the result, completing with `null` when the leader lock cannot be acquired
 */
fun <T> Database.runVirtualIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> {
    val election = ExposedJdbcLeaderElector(this, options)
    return ExposedJdbcVirtualThreadLeaderElector(election).runAsyncIfLeader(lockName, action)
}

/**
 * Runs a group leader election on this [Database].
 *
 * ```kotlin
 * val opts = ExposedJdbcLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 4),
 * )
 * val result = db.runIfLeaderGroup("worker-pool", opts) { processChunk() }
 * // up to 4 nodes run concurrently; returns null when all slots are occupied
 * ```
 *
 * @param lockName lock name used for group leader election
 * @param options group election options; defaults to [ExposedJdbcLeaderGroupElectionOptions.Default]
 * @param action the work to execute when a group slot is acquired
 * @return [action] result, or `null` when no slot can be acquired
 */
fun <T> Database.runIfLeaderGroup(
    lockName: String,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * Runs a group leader election on this [Database] asynchronously.
 *
 * ```kotlin
 * val future = db.runAsyncIfLeaderGroup(
 *     lockName = "parallel-batch",
 *     options = ExposedJdbcLeaderGroupElectionOptions(
 *         leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 3),
 *     ),
 * ) {
 *     CompletableFuture.supplyAsync { processChunk() }
 * }
 * ```
 *
 * @param lockName lock name used for group leader election
 * @param executor async [Executor]; defaults to [VirtualThreadExecutor]
 * @param options group election options; defaults to [ExposedJdbcLeaderGroupElectionOptions.Default]
 * @param action the async work to execute when a group slot is acquired
 * @return [CompletableFuture] holding the result, completing with `null` when no slot can be acquired
 */
fun <T> Database.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
