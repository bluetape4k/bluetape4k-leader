package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.resolveLeaderBackendDiagnosticsProvider
import io.bluetape4k.support.requireNotNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * 주어진 tenant ID로 namespace를 만들고, 이 elector의 모든 lock 이름에 tenant 범위를 적용하는 wrapper를 반환합니다.
 * @param tenantId lock 이름을 tenant별로 구분하는 식별자
 * @return tenant namespace를 적용하는 elector wrapper
 */
fun LeaderElector.forTenant(tenantId: String): LeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * 주어진 namespace를 이 elector의 lock 이름과 `LeaderSlot.lockName`에 적용하는 wrapper를 반환합니다.
 * @param namespace delegate에 전달하기 전에 lock 이름에 적용할 tenant namespace
 * @return namespace를 적용하는 elector wrapper
 */
fun LeaderElector.forTenant(namespace: TenantLockNamespace): LeaderElector =
    TenantScopedLeaderElector(this, namespace)

/**
 * 주어진 tenant ID로 namespace를 만들고, 이 group elector의 lock 이름에 tenant 범위를 적용하는 wrapper를 반환합니다.
 * @param tenantId lock 이름을 tenant별로 구분하는 식별자
 * @return tenant namespace를 적용하는 group elector wrapper
 */
fun LeaderGroupElector.forTenant(tenantId: String): LeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * 주어진 namespace를 이 group elector의 lock 이름과 `LeaderSlot.lockName`에 적용하는 wrapper를 반환합니다.
 * @param namespace delegate에 전달하기 전에 lock 이름에 적용할 tenant namespace
 * @return namespace를 적용하는 group elector wrapper
 */
fun LeaderGroupElector.forTenant(namespace: TenantLockNamespace): LeaderGroupElector =
    TenantScopedLeaderGroupElector(this, namespace)

/**
 * 주어진 tenant ID로 namespace를 만들고, 이 virtual-thread elector의 lock 이름에 tenant 범위를 적용하는 wrapper를 반환합니다.
 * @param tenantId lock 이름을 tenant별로 구분하는 식별자
 * @return tenant namespace를 적용하는 virtual-thread elector wrapper
 */
fun VirtualThreadLeaderElector.forTenant(tenantId: String): VirtualThreadLeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * 주어진 namespace를 이 virtual-thread elector의 lock 이름과 `LeaderSlot.lockName`에 적용하는 wrapper를 반환합니다.
 * @param namespace delegate에 전달하기 전에 lock 이름에 적용할 tenant namespace
 * @return namespace를 적용하는 virtual-thread elector wrapper
 */
fun VirtualThreadLeaderElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderElector =
    TenantScopedVirtualThreadLeaderElector(this, namespace)

/**
 * 주어진 tenant ID로 namespace를 만들고, 이 virtual-thread group elector의 lock 이름에 tenant 범위를 적용하는 wrapper를 반환합니다.
 * @param tenantId lock 이름을 tenant별로 구분하는 식별자
 * @return tenant namespace를 적용하는 virtual-thread group elector wrapper
 */
fun VirtualThreadLeaderGroupElector.forTenant(tenantId: String): VirtualThreadLeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * 주어진 namespace를 이 virtual-thread group elector의 lock 이름과 `LeaderSlot.lockName`에 적용하는 wrapper를 반환합니다.
 * @param namespace delegate에 전달하기 전에 lock 이름에 적용할 tenant namespace
 * @return namespace를 적용하는 virtual-thread group elector wrapper
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
): LeaderElector, LeaderLeaseAcquirerSupport, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override val leaseCapabilityAvailable: Boolean
        get() = ((delegate as? LeaderLeaseAcquirerSupport)?.leaseCapabilityAvailable ?: delegate) is LeaderLeaseAcquirer

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        val acquirer = (delegate as? LeaderLeaseAcquirer).requireNotNull {
            "The tenant-scoped elector delegate does not expose request-lease capability"
        }
        object: LeaderLeaseAcquirer {
            override val configuredOptions: LeaderElectionOptions
                get() = acquirer.configuredOptions

            override fun tryAcquire(lockName: String): LeaderLeaseHandle? =
                acquirer.tryAcquire(namespace.lockName(lockName))

            override fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle? =
                acquirer.tryAcquire(slot.scoped())
        }
    }

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
): LeaderGroupElector, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

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
): VirtualThreadLeaderElector, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

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
): VirtualThreadLeaderGroupElector, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

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
