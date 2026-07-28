package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> Database.runIfLeader(
    lockName: String,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> Database.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderElectionOptions = ExposedJdbcLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)

/**
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
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
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> Database.runIfLeaderGroup(
    lockName: String,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = ExposedJdbcLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> Database.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: ExposedJdbcLeaderGroupElectionOptions = ExposedJdbcLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = ExposedJdbcLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
