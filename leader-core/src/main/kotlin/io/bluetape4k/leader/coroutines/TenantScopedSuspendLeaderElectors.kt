package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.TenantLockNamespace
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.resolveLeaderBackendDiagnosticsProvider
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotNull

/**
 * 모든 lock 이름에 기본 prefix를 사용하는 tenant namespace를 적용한 elector wrapper를 반환합니다.
 * @param tenantId lock key에 포함할 tenant 식별자
 * @return 호출마다 tenant namespace를 적용하는 suspend elector
 */
fun SuspendLeaderElector.forTenant(tenantId: String): SuspendLeaderElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * 지정한 tenant namespace를 모든 lock name, slot, 상태 조회 및 request-lease 호출에 적용한 wrapper를 반환합니다.
 * @param namespace tenant별 lock key를 만드는 규칙
 * @return namespace를 적용하는 suspend elector
 */
fun SuspendLeaderElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderElector =
    TenantScopedSuspendLeaderElector(this, namespace)

/**
 * 모든 group lock 이름에 기본 prefix를 사용하는 tenant namespace를 적용한 elector wrapper를 반환합니다.
 * @param tenantId lock key에 포함할 tenant 식별자
 * @return 호출마다 tenant namespace를 적용하는 suspend group elector
 */
fun SuspendLeaderGroupElector.forTenant(tenantId: String): SuspendLeaderGroupElector =
    forTenant(TenantLockNamespace(tenantId))

/**
 * 지정한 tenant namespace를 모든 group lock name, slot, 상태 조회에 적용한 wrapper를 반환합니다.
 * @param namespace tenant별 lock key를 만드는 규칙
 * @return namespace를 적용하는 suspend group elector
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
): SuspendLeaderElector, SuspendLeaderLeaseAcquirerSupport, LeaderBackendDiagnosticsAware {

    companion object: KLoggingChannel()

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override val leaseCapabilityAvailable: Boolean
        get() = ((delegate as? SuspendLeaderLeaseAcquirerSupport)?.leaseCapabilityAvailable
            ?: delegate) is SuspendLeaderLeaseAcquirer

    override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer by lazy {
        val acquirer = (delegate as? SuspendLeaderLeaseAcquirer).requireNotNull {
            "The tenant-scoped suspend elector delegate does not expose request-lease capability"
        }
        object: SuspendLeaderLeaseAcquirer {
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
): SuspendLeaderGroupElector, LeaderBackendDiagnosticsAware {

    companion object: KLoggingChannel()

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
