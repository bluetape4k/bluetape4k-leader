package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.validateLockName
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Hazelcast `IMap`에 단일 lock을 등록하고, 획득 시에만 `action`을 실행합니다. 경합 중이면 `null`을 반환합니다. */
inline fun <T> HazelcastInstance.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    jobName.validateLockName("jobName")
    return HazelcastLeaderElector(this, options).runIfLeader(jobName) { action() }
}

/** 단일 Hazelcast lock을 비동기로 획득한 뒤, 획득 시 `action`의 `CompletableFuture`를 평탄화합니다. 경합이면 완료값 `null`을 반환합니다. */
inline fun <T> HazelcastInstance.runAsyncIfLeader(
    jobName: String,
    executor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    jobName.validateLockName("jobName")
    return HazelcastLeaderElector(this, options).runAsyncIfLeader(jobName, executor) { action() }
}


/** Hazelcast `IMap`의 `jobName:slot:n` 키 중 하나를 획득해 그룹 작업을 실행합니다. 모든 슬롯이 사용 중이면 `null`을 반환합니다. */
inline fun <T> HazelcastInstance.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName("lockName")
    return HazelcastLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}

/** 단일 Hazelcast lock 획득을 중단 가능한 코루틴으로 수행하고, 획득 시에만 `action`을 실행합니다. 경합이면 `null`을 반환합니다. */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.validateLockName("jobName")
    return HazelcastSuspendLeaderElector(this, options).runIfLeader(jobName) { action() }
}

/** Hazelcast 그룹 슬롯을 중단 가능한 코루틴으로 획득해 `action`을 실행하며, 슬롯이 없으면 `null`을 반환합니다. */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    lockName.validateLockName("lockName")
    return HazelcastSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}
