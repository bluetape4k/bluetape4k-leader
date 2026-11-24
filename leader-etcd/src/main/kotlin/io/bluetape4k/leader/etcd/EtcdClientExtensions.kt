package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.validateLockName
import io.etcd.jetcd.Client
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** [this]에서 [lockName]을 획득했을 때만 blocking [action]을 실행하며 경합 시 null을 반환합니다. */
inline fun <T> Client.runIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName()
    return EtcdLeaderElector(this, options).runIfLeader(lockName) { action() }
}

/** [executor]에서 [lockName] 획득과 비동기 [action]을 실행하며 경합 시 null로 완료합니다. */
fun <T> Client.runAsyncIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return EtcdLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
}

/** [this]의 그룹 슬롯을 획득했을 때만 blocking [action]을 실행하며 사용 가능한 슬롯이 없으면 null입니다. */
inline fun <T> Client.runIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName()
    return EtcdLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}

/** [executor]에서 [lockName]의 그룹 슬롯 획득과 비동기 [action]을 실행하며 슬롯 경합 시 null로 완료합니다. */
fun <T> Client.runAsyncIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return EtcdLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
}

//
//  Suspend
//


/** [this]에서 [lockName]을 획득했을 때만 suspend [action]을 실행하며 경합 시 null을 반환합니다. */
suspend fun <T> Client.suspendRunIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.validateLockName()
    return EtcdSuspendLeaderElector(this, options).runIfLeader(lockName, action)
}

/** [this]의 그룹 슬롯을 획득했을 때만 suspend [action]을 실행하며 경합 시 null을 반환합니다. */
suspend fun <T> Client.suspendRunIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.validateLockName()
    return EtcdSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
}


//
//  Virtual Threads
//

/** virtual thread에서 [lockName]을 획득하고 blocking [action]을 실행하는 future를 반환합니다. */
fun <T> Client.runVirtualIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> {
    lockName.validateLockName()
    return EtcdVirtualThreadLeaderElector(EtcdLeaderElector(this, options))
        .runAsyncIfLeader(lockName, action)
}

/** virtual thread에서 [lockName]의 그룹 슬롯을 획득하고 [action]을 실행하는 future를 반환합니다. */
fun <T> Client.runVirtualIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> {
    lockName.validateLockName()
    return EtcdVirtualThreadLeaderGroupElector(EtcdLeaderGroupElector(this, options))
        .runAsyncIfLeader(lockName, action)
}
