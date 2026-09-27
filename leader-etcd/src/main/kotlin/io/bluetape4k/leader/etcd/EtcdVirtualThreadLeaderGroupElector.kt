package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge

class EtcdVirtualThreadLeaderGroupElector(
    private val delegate: EtcdLeaderGroupElector,
): VirtualThreadLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by EtcdLeaderBackendDiagnostics {


    override val maxLeaders: Int
        get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int = delegate.activeCount(lockName)

    override fun availableSlots(lockName: String): Int = delegate.availableSlots(lockName)

    override fun state(lockName: String): LeaderGroupState = delegate.state(lockName)

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(lockName, action) }

    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(slot, action) }

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> =
        LeaderFutureBridge.propagateCancellation(
            virtualFuture { delegate.runIfLeaderResult(slot, action) }
        )
}
