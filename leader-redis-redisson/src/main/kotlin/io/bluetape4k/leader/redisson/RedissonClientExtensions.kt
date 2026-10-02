package io.bluetape4k.leader.redisson

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.redisson.api.RedissonClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> RedissonClient.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    jobName.validateLockName("jobName")
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> RedissonClient.runAsyncIfLeader(
    jobName: String,
    executor: Executor = ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    jobName.validateLockName("jobName")
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runAsyncIfLeader(jobName, executor) { action() }
}

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> RedissonClient.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return RedissonLeaderGroupElector(this, options)
        .runIfLeader(lockName) {
            action()
        }
}

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> RedissonClient.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.validateLockName("jobName")

    val leaderElection = RedissonSuspendLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}

/**
 * `선언` 호출은 Redis Redisson backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend fun <T> RedissonClient.runSuspendIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    options.maxLeaders.requirePositiveNumber("maxLeaders")
    return RedissonSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
}
