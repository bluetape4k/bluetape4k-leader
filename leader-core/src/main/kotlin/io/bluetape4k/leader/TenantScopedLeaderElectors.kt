package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Wraps this [LeaderElector] so every caller-facing lock name is scoped to [tenantId].
 */
fun LeaderElector.forTenant(tenantId: String): LeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * Wraps this [LeaderElector] so every caller-facing lock name is scoped to [namespace].
 */
fun LeaderElector.forTenant(namespace: TenantLockNamespace): LeaderElector =
    TenantScopedLeaderElector(this, namespace)

/**
 * Wraps this [LeaderGroupElector] so every caller-facing lock name is scoped to [tenantId].
 */
fun LeaderGroupElector.forTenant(tenantId: String): LeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * Wraps this [LeaderGroupElector] so every caller-facing lock name is scoped to [namespace].
 */
fun LeaderGroupElector.forTenant(namespace: TenantLockNamespace): LeaderGroupElector =
    TenantScopedLeaderGroupElector(this, namespace)

/**
 * Wraps this [VirtualThreadLeaderElector] so every caller-facing lock name is scoped to [tenantId].
 */
fun VirtualThreadLeaderElector.forTenant(tenantId: String): VirtualThreadLeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * Wraps this [VirtualThreadLeaderElector] so every caller-facing lock name is scoped to [namespace].
 */
fun VirtualThreadLeaderElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderElector =
    TenantScopedVirtualThreadLeaderElector(this, namespace)

/**
 * Wraps this [VirtualThreadLeaderGroupElector] so every caller-facing lock name is scoped to [tenantId].
 */
fun VirtualThreadLeaderGroupElector.forTenant(tenantId: String): VirtualThreadLeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * Wraps this [VirtualThreadLeaderGroupElector] so every caller-facing lock name is scoped to [namespace].
 */
fun VirtualThreadLeaderGroupElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderGroupElector =
    TenantScopedVirtualThreadLeaderGroupElector(this, namespace)

internal class TenantScopedLeaderElector(
    private val delegate: LeaderElector,
    private val namespace: TenantLockNamespace,
) : LeaderElector {

    override fun state(lockName: String): LeaderState =
        delegate.state(namespace.lockName(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        delegate.runIfLeader(namespace.lockName(lockName), action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        delegate.runIfLeader(slot.scoped(), action)

    override fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> =
        delegate.runIfLeaderResult(namespace.lockName(lockName), action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> =
        delegate.runIfLeaderResult(slot.scoped(), action)

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        delegate.runAsyncIfLeader(namespace.lockName(lockName), executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        delegate.runAsyncIfLeader(slot.scoped(), executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> =
        delegate.runAsyncIfLeaderResult(slot.scoped(), executor, action)

    private fun LeaderSlot.scoped(): LeaderSlot =
        copy(lockName = namespace.lockName(lockName))
}

internal class TenantScopedLeaderGroupElector(
    private val delegate: LeaderGroupElector,
    private val namespace: TenantLockNamespace,
) : LeaderGroupElector {

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(namespace.lockName(lockName))

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(namespace.lockName(lockName))

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(namespace.lockName(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        delegate.runIfLeader(namespace.lockName(lockName), action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        delegate.runIfLeader(slot.scoped(), action)

    override fun <T> runIfLeaderResult(lockName: String, action: () -> T): LeaderRunResult<T> =
        delegate.runIfLeaderResult(namespace.lockName(lockName), action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> =
        delegate.runIfLeaderResult(slot.scoped(), action)

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        delegate.runAsyncIfLeader(namespace.lockName(lockName), executor, action)

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        delegate.runAsyncIfLeader(slot.scoped(), executor, action)

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> =
        delegate.runAsyncIfLeaderResult(slot.scoped(), executor, action)

    private fun LeaderSlot.scoped(): LeaderSlot =
        copy(lockName = namespace.lockName(lockName))
}

internal class TenantScopedVirtualThreadLeaderElector(
    private val delegate: VirtualThreadLeaderElector,
    private val namespace: TenantLockNamespace,
) : VirtualThreadLeaderElector {

    override fun state(lockName: String): LeaderState =
        delegate.state(namespace.lockName(lockName))

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        delegate.runAsyncIfLeader(namespace.lockName(lockName), action)

    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        delegate.runAsyncIfLeader(slot.scoped(), action)

    override fun <T> runAsyncIfLeaderResult(slot: LeaderSlot, action: () -> T): VirtualFuture<LeaderRunResult<T>> =
        delegate.runAsyncIfLeaderResult(slot.scoped(), action)

    private fun LeaderSlot.scoped(): LeaderSlot =
        copy(lockName = namespace.lockName(lockName))
}

internal class TenantScopedVirtualThreadLeaderGroupElector(
    private val delegate: VirtualThreadLeaderGroupElector,
    private val namespace: TenantLockNamespace,
) : VirtualThreadLeaderGroupElector {

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(namespace.lockName(lockName))

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(namespace.lockName(lockName))

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(namespace.lockName(lockName))

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        delegate.runAsyncIfLeader(namespace.lockName(lockName), action)

    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        delegate.runAsyncIfLeader(slot.scoped(), action)

    override fun <T> runAsyncIfLeaderResult(slot: LeaderSlot, action: () -> T): VirtualFuture<LeaderRunResult<T>> =
        delegate.runAsyncIfLeaderResult(slot.scoped(), action)

    private fun LeaderSlot.scoped(): LeaderSlot =
        copy(lockName = namespace.lockName(lockName))
}
