package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.validateLockName
import io.etcd.jetcd.Client
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

inline fun <T> Client.runIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName()
    return EtcdLeaderElector(this, options).runIfLeader(lockName) { action() }
}

fun <T> Client.runAsyncIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return EtcdLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
}

inline fun <T> Client.runIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName()
    return EtcdLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}

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


suspend fun <T> Client.suspendRunIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? {
    lockName.validateLockName()
    return EtcdSuspendLeaderElector(this, options).runIfLeader(lockName, action)
}

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

fun <T> Client.runVirtualIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    EtcdVirtualThreadLeaderElector(EtcdLeaderElector(this, options))
        .runAsyncIfLeader(lockName, action)

fun <T> Client.runVirtualIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> {
    lockName.validateLockName()
    return EtcdVirtualThreadLeaderGroupElector(EtcdLeaderGroupElector(this, options))
        .runAsyncIfLeader(lockName, action)
}
