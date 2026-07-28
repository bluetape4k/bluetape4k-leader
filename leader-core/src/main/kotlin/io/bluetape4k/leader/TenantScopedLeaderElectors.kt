package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `LeaderElector`는 blocking leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param tenantId tenant scope를 구분하는 식별자입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderElector.forTenant(tenantId: String): LeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * `LeaderElector`는 blocking leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param namespace tenant별 lock 이름을 구성하는 namespace 규칙입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderElector.forTenant(namespace: TenantLockNamespace): LeaderElector =
    TenantScopedLeaderElector(this, namespace)

/**
 * `LeaderGroupElector`는 여러 slot을 허용하는 blocking group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param tenantId tenant scope를 구분하는 식별자입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderGroupElector.forTenant(tenantId: String): LeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * `LeaderGroupElector`는 여러 slot을 허용하는 blocking group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param namespace tenant별 lock 이름을 구성하는 namespace 규칙입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderGroupElector.forTenant(namespace: TenantLockNamespace): LeaderGroupElector =
    TenantScopedLeaderGroupElector(this, namespace)

/**
 * `VirtualThreadLeaderElector`는 virtual thread 기반 leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param tenantId tenant scope를 구분하는 식별자입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun VirtualThreadLeaderElector.forTenant(tenantId: String): VirtualThreadLeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * `VirtualThreadLeaderElector`는 virtual thread 기반 leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param namespace tenant별 lock 이름을 구성하는 namespace 규칙입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun VirtualThreadLeaderElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderElector =
    TenantScopedVirtualThreadLeaderElector(this, namespace)

/**
 * `VirtualThreadLeaderGroupElector`는 여러 slot을 허용하는 virtual thread group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param tenantId tenant scope를 구분하는 식별자입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun VirtualThreadLeaderGroupElector.forTenant(tenantId: String): VirtualThreadLeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * `VirtualThreadLeaderGroupElector`는 여러 slot을 허용하는 virtual thread group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param namespace tenant별 lock 이름을 구성하는 namespace 규칙입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun VirtualThreadLeaderGroupElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderGroupElector =
    TenantScopedVirtualThreadLeaderGroupElector(this, namespace)

/**
 * `TenantScopedLeaderElector`는 delegate의 single leader election API를 tenant namespace로 감싸는 internal adapter입니다.
 *
 * @property delegate 실제 lock 획득, 상태 조회, async 실행을 수행하는 원본 elector입니다.
 * @property namespace 호출자가 전달한 lock 이름을 tenant별 lock 이름으로 변환하는 namespace 규칙입니다.
 */
internal class TenantScopedLeaderElector(
    private val delegate: LeaderElector,
    private val namespace: TenantLockNamespace,
) : LeaderElector {

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

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

/**
 * `TenantScopedLeaderGroupElector`는 delegate의 group leader election API를 tenant namespace로 감싸는 internal adapter입니다.
 *
 * @property delegate 실제 group slot 획득, 상태 조회, async 실행을 수행하는 원본 group elector입니다.
 * @property namespace 호출자가 전달한 lock 이름을 tenant별 group lock 이름으로 변환하는 namespace 규칙입니다.
 */
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

/**
 * `TenantScopedVirtualThreadLeaderElector`는 virtual thread elector 호출을 tenant namespace로 변환하는 internal adapter입니다.
 *
 * @property delegate 실제 virtual thread 기반 single leader election을 수행하는 원본 elector입니다.
 * @property namespace `LeaderSlot`과 lock 이름을 tenant scope로 변환하는 namespace 규칙입니다.
 */
internal class TenantScopedVirtualThreadLeaderElector(
    private val delegate: VirtualThreadLeaderElector,
    private val namespace: TenantLockNamespace,
) : VirtualThreadLeaderElector {

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

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

/**
 * `TenantScopedVirtualThreadLeaderGroupElector`는 virtual thread group elector 호출을 tenant namespace로 변환하는 internal adapter입니다.
 *
 * @property delegate 실제 virtual thread 기반 group leader election을 수행하는 원본 group elector입니다.
 * @property namespace `LeaderSlot`과 lock 이름을 tenant scope로 변환하는 namespace 규칙입니다.
 */
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
