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

/** `jobName`의 `RLock`을 획득한 경우에만 동기 `action`을 실행하고, 정상 경합이면 `null`을 반환합니다. */
inline fun <T> RedissonClient.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    jobName.validateLockName("jobName")
    val leaderElection = RedissonLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}

/** `RLock` 획득과 action future 정리를 `executor`에서 수행하며, 경합이면 완료값 `null`을 반환합니다. */
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

/** `lg:{$lockName}` permit semaphore에서 한 슬롯을 획득한 경우에만 동기 `action`을 실행합니다. 슬롯이 없으면 `null`입니다. */
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

/** coroutine에서 `RLock`을 획득한 경우에만 suspend `action`을 실행하고, 정상 경합이면 `null`을 반환합니다. */
suspend inline fun <T> RedissonClient.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.validateLockName("jobName")

    val leaderElection = RedissonSuspendLeaderElector(this, options)
    return leaderElection.runIfLeader(jobName) { action() }
}

/** coroutine에서 `lg:{$lockName}` permit을 획득해 suspend `action`을 실행하고, 슬롯이 없으면 `null`을 반환합니다. */
suspend fun <T> RedissonClient.runSuspendIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    options.maxLeaders.requirePositiveNumber("maxLeaders")
    return RedissonSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
}
