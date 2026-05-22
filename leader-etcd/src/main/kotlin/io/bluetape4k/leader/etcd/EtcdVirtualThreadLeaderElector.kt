package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.etcd.jetcd.Client

/**
 * Virtual-thread adapter for [EtcdLeaderElector].
 */
class EtcdVirtualThreadLeaderElector(
    private val delegate: EtcdLeaderElector,
) : VirtualThreadLeaderElector {

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}

fun <T> Client.runVirtualIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    EtcdVirtualThreadLeaderElector(EtcdLeaderElector(this, options)).runAsyncIfLeader(lockName, action)
