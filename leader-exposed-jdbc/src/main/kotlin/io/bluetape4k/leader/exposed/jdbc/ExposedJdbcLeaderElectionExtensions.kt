package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 이 [Database]에 대한 단일 리더 선출을 실행합니다.
 *
 * `ExposedJdbcLeaderElection(this, options).runIfLeader(lockName, action)`의 편의 함수입니다.
 * [ExposedJdbcLeaderElection.invoke]를 통해 ensureSchema가 보장됩니다.
 *
 * ```kotlin
 * val report = db.runIfLeader("daily-report") { generateReport() }
 *     ?: return // 리더가 아니면 건너뜀
 * ```
 *
 * @param lockName 리더 선출에 사용할 락 이름
 * @param options 선출 옵션. 기본값 [ExposedJdbcLeaderElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 작업
 * @return [action] 실행 결과, 리더 획득 실패 시 `null`
 */
fun <T> Database.runIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderElection(this, options).runIfLeader(lockName, action)

/**
 * 이 [Database]에 대한 단일 리더 선출을 비동기로 실행합니다.
 *
 * ```kotlin
 * val future: CompletableFuture<Report?> = db.runAsyncIfLeader("nightly-sync") {
 *     CompletableFuture.supplyAsync { syncData() }
 * }
 * ```
 *
 * @param lockName 리더 선출에 사용할 락 이름
 * @param executor 비동기 실행 [Executor]. 기본값 [VirtualThreadExecutor]
 * @param options 선출 옵션. 기본값 [ExposedJdbcLeaderElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 비동기 작업
 * @return 실행 결과를 담은 [CompletableFuture]. 리더 획득 실패 시 `null`로 완료됨
 */
fun <T> Database.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderElection(this, options).runAsyncIfLeader(lockName, executor, action)

/**
 * 이 [Database]에 대한 단일 리더 선출을 Virtual Thread에서 비동기로 실행합니다.
 *
 * ```kotlin
 * val result: String? = db.runVirtualIfLeader("vt-job") { computeHeavy() }
 *     .get(5, TimeUnit.SECONDS)
 * ```
 *
 * @param lockName 리더 선출에 사용할 락 이름
 * @param options 선출 옵션. 기본값 [ExposedJdbcLeaderElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 작업
 * @return 실행 결과를 담은 [VirtualFuture]. 리더 획득 실패 시 `null`로 완료됨
 */
fun <T> Database.runVirtualIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> {
    val election = ExposedJdbcLeaderElection(this, options)
    return ExposedJdbcVirtualThreadLeaderElection(election).runAsyncIfLeader(lockName, action)
}

/**
 * 이 [Database]에 대한 그룹 리더 선출을 실행합니다.
 *
 * ```kotlin
 * val opts = ExposedJdbcLeaderGroupElectionOptions(
 *     leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 4),
 * )
 * val result = db.runIfLeaderGroup("worker-pool", opts) { processChunk() }
 * // 최대 4개 노드 동시 실행, 슬롯 만석 시 null
 * ```
 *
 * @param lockName 리더 그룹 선출에 사용할 락 이름
 * @param options 그룹 선출 옵션. 기본값 [ExposedJdbcLeaderGroupElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 작업
 * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
 */
fun <T> Database.runIfLeaderGroup(
    lockName: String,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderGroupElection(this, options).runIfLeader(lockName, action)

/**
 * 이 [Database]에 대한 그룹 리더 선출을 비동기로 실행합니다.
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
 * @param lockName 리더 그룹 선출에 사용할 락 이름
 * @param executor 비동기 실행 [Executor]. 기본값 [VirtualThreadExecutor]
 * @param options 그룹 선출 옵션. 기본값 [ExposedJdbcLeaderGroupElectionOptions.Default]
 * @param action 리더 획득 성공 시 실행할 비동기 작업
 * @return 실행 결과를 담은 [CompletableFuture]. 슬롯 획득 실패 시 `null`로 완료됨
 */
fun <T> Database.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderGroupElection(this, options).runAsyncIfLeader(lockName, executor, action)
