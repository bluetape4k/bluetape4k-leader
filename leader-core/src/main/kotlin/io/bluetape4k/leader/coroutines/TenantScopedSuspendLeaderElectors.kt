package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.TenantLockNamespace

/**
 * Wraps this [SuspendLeaderElector] so every caller-facing lock name is scoped to [tenantId].
 */
fun SuspendLeaderElector.forTenant(tenantId: String): SuspendLeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * Wraps this [SuspendLeaderElector] so every caller-facing lock name is scoped to [namespace].
 */
fun SuspendLeaderElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderElector =
    TenantScopedSuspendLeaderElector(this, namespace)

/**
 * Wraps this [SuspendLeaderGroupElector] so every caller-facing lock name is scoped to [tenantId].
 */
fun SuspendLeaderGroupElector.forTenant(tenantId: String): SuspendLeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * Wraps this [SuspendLeaderGroupElector] so every caller-facing lock name is scoped to [namespace].
 */
fun SuspendLeaderGroupElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderGroupElector =
    TenantScopedSuspendLeaderGroupElector(this, namespace)

internal class TenantScopedSuspendLeaderElector(
    private val delegate: SuspendLeaderElector,
    private val namespace: TenantLockNamespace,
) : SuspendLeaderElector {

    override fun state(lockName: String): LeaderState =
        delegate.state(namespace.lockName(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        delegate.runIfLeader(namespace.lockName(lockName), action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        delegate.runIfLeader(slot.scoped(), action)

    override suspend fun <T> runIfLeaderResultSuspend(
        lockName: String,
        action: suspend () -> T,
    ): LeaderRunResult<T> =
        delegate.runIfLeaderResultSuspend(namespace.lockName(lockName), action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> =
        delegate.runIfLeaderResultSuspend(slot.scoped(), action)

    private fun LeaderSlot.scoped(): LeaderSlot =
        copy(lockName = namespace.lockName(lockName))
}

internal class TenantScopedSuspendLeaderGroupElector(
    private val delegate: SuspendLeaderGroupElector,
    private val namespace: TenantLockNamespace,
) : SuspendLeaderGroupElector {

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(namespace.lockName(lockName))

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(namespace.lockName(lockName))

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(namespace.lockName(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        delegate.runIfLeader(namespace.lockName(lockName), action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        delegate.runIfLeader(slot.scoped(), action)

    override suspend fun <T> runIfLeaderResultSuspend(
        lockName: String,
        action: suspend () -> T,
    ): LeaderRunResult<T> =
        delegate.runIfLeaderResultSuspend(namespace.lockName(lockName), action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> =
        delegate.runIfLeaderResultSuspend(slot.scoped(), action)

    private fun LeaderSlot.scoped(): LeaderSlot =
        copy(lockName = namespace.lockName(lockName))
}
