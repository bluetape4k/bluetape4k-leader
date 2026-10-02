package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider

class ExposedJdbcVirtualThreadLeaderGroupElector(
    private val delegate: ExposedJdbcLeaderGroupElector,
): VirtualThreadLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics {

    override fun <T> runAsyncIfLeader(
        lockName: String,
        action: () -> T,
    ): VirtualFuture<T?> = virtualFuture {
        delegate.runIfLeader(lockName, action)
    }

    override val maxLeaders: Int
        get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int {
        return delegate.activeCount(lockName)
    }

    override fun availableSlots(lockName: String): Int {
        return delegate.availableSlots(lockName)
    }

    override fun state(lockName: String): LeaderGroupState {
        return delegate.state(lockName)
    }
}
