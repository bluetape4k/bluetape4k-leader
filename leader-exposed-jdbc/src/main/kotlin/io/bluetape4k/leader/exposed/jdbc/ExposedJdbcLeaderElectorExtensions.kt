package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * [this] 데이터베이스에서 [lockName]을 획득했을 때만 [action]을 실행합니다.
 * 경합으로 획득하지 못하면 null을 반환하며, 스키마가 아직 없으면 elector 생성 중 보장합니다.
 */
fun <T> Database.runIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): T? =
    ExposedJdbcLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * [this] 데이터베이스에서 [lockName]을 비동기로 획득한 뒤 [action]을 실행합니다.
 * 기본 [executor]는 virtual thread executor이며, 경합 시 null로 완료합니다.
 */
fun <T> Database.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ExposedJdbcLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)

/**
 * [this] 데이터베이스에서 virtual thread로 [lockName]을 획득하고 [action]을 실행합니다.
 * 경합으로 획득하지 못하면 null을 반환합니다.
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
 * [this] 데이터베이스의 그룹 슬롯을 획득했을 때만 [action]을 실행합니다.
 * 동시에 허용된 슬롯이 없으면 null을 반환하고, 획득한 슬롯은 action 종료 후 반납합니다.
 */
fun <T> Database.runIfLeaderGroup(
    lockName: String,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? =
    ExposedJdbcLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * [this] 데이터베이스에서 그룹 슬롯을 비동기로 획득한 뒤 [action]을 실행합니다.
 * 기본 [executor]에서 대기·작업을 진행하며 슬롯을 얻지 못하면 null로 완료합니다.
 */
fun <T> Database.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ExposedJdbcLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
