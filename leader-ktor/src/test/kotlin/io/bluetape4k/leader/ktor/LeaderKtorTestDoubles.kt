package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import java.util.concurrent.atomic.AtomicInteger

internal class FakeSuspendLeaderElector(
    private val stateValue: LeaderState = LeaderState.empty("job"),
    private val stateReads: AtomicInteger? = null,
    override val supportsAuditLeaderState: Boolean = true,
) : SuspendLeaderElector {
    override fun state(lockName: String): LeaderState {
        stateReads?.incrementAndGet()
        return stateValue.copy(lockName = lockName)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
}

internal class AutoCloseableFakeSuspendLeaderElector(
    private val delegate: FakeSuspendLeaderElector = FakeSuspendLeaderElector(),
) : SuspendLeaderElector, AutoCloseable {
    val closeCount = AtomicInteger()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override fun state(lockName: String): LeaderState = delegate.state(lockName)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        delegate.runIfLeader(lockName, action)

    override fun close() {
        closeCount.incrementAndGet()
    }
}
