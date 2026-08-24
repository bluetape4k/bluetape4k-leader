package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.TenantLockNamespace
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.resolveLeaderBackendDiagnosticsProvider

/**
 * `SuspendLeaderElector`는 coroutine suspend leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param tenantId tenant scope를 구분하는 식별자입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun SuspendLeaderElector.forTenant(tenantId: String): SuspendLeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * `SuspendLeaderElector`는 coroutine suspend leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param namespace tenant별 lock 이름을 구성하는 namespace 규칙입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun SuspendLeaderElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderElector =
    TenantScopedSuspendLeaderElector(this, namespace)

/**
 * `SuspendLeaderGroupElector`는 여러 slot을 허용하는 coroutine group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param tenantId tenant scope를 구분하는 식별자입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun SuspendLeaderGroupElector.forTenant(tenantId: String): SuspendLeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * `SuspendLeaderGroupElector`는 여러 slot을 허용하는 coroutine group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param namespace tenant별 lock 이름을 구성하는 namespace 규칙입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun SuspendLeaderGroupElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderGroupElector =
    TenantScopedSuspendLeaderGroupElector(this, namespace)

/**
 * `TenantScopedSuspendLeaderElector`는 coroutine single leader election 호출을 tenant namespace로 변환하는 internal adapter입니다.
 *
 * @property delegate 실제 suspend leader election과 상태 조회를 수행하는 원본 elector입니다.
 * @property namespace 호출자가 전달한 lock 이름과 `LeaderSlot`을 tenant scope로 변환하는 namespace 규칙입니다.
 */
internal class TenantScopedSuspendLeaderElector(
    private val delegate: SuspendLeaderElector,
    private val namespace: TenantLockNamespace,
) : SuspendLeaderElector, SuspendLeaderLeaseAcquirerSupport, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override val leaseCapabilityAvailable: Boolean
        get() = (delegate as? SuspendLeaderLeaseAcquirerSupport)?.leaseCapabilityAvailable
            ?: delegate is SuspendLeaderLeaseAcquirer

    override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer by lazy {
        val acquirer = requireNotNull(delegate as? SuspendLeaderLeaseAcquirer) {
            "The tenant-scoped suspend elector delegate does not expose request-lease capability"
        }
        object : SuspendLeaderLeaseAcquirer {
            override val configuredOptions: io.bluetape4k.leader.LeaderElectionOptions
                get() = acquirer.configuredOptions

            override suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle? =
                acquirer.tryAcquire(namespace.lockName(lockName))

            override suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle? =
                acquirer.tryAcquire(slot.scoped())
        }
    }

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

/**
 * `TenantScopedSuspendLeaderGroupElector`는 coroutine group leader election 호출을 tenant namespace로 변환하는 internal adapter입니다.
 *
 * @property delegate 실제 suspend group leader election과 상태 조회를 수행하는 원본 group elector입니다.
 * @property namespace 호출자가 전달한 lock 이름과 `LeaderSlot`을 tenant scope로 변환하는 namespace 규칙입니다.
 */
internal class TenantScopedSuspendLeaderGroupElector(
    private val delegate: SuspendLeaderGroupElector,
    private val namespace: TenantLockNamespace,
) : SuspendLeaderGroupElector, LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

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
