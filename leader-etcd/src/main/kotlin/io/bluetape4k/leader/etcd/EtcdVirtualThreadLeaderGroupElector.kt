package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge

/**
 * `EtcdVirtualThreadLeaderGroupElector`는 etcd 그룹 elector 호출을 virtual thread에서 실행합니다.
 *
 * lock-name 경로는 경합 시 `null`을 반환하고, [LeaderSlot] 결과는 `leaderId`를 보존합니다.
 * 반환된 future를 취소하면 실행 중인 action에 interruption을 전달하고 획득한 슬롯을 반납합니다.
 *
 * @property delegate 슬롯 획득·lease·history·반납을 담당하는 etcd blocking elector입니다.
 */
class EtcdVirtualThreadLeaderGroupElector(
    private val delegate: EtcdLeaderGroupElector,
): VirtualThreadLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by EtcdLeaderBackendDiagnostics {


    /** 허용된 최대 동시 그룹 leader 수입니다. */
    override val maxLeaders: Int
        get() = delegate.maxLeaders

    /** [lockName]에 현재 활성화된 leader 수를 반환합니다. */
    override fun activeCount(lockName: String): Int = delegate.activeCount(lockName)

    /** [lockName]에 남아 있는 그룹 슬롯 수를 반환합니다. */
    override fun availableSlots(lockName: String): Int = delegate.availableSlots(lockName)

    /** [lockName]의 현재 그룹 선거 상태를 반환합니다. */
    override fun state(lockName: String): LeaderGroupState = delegate.state(lockName)

    /** lock 이름으로 그룹 슬롯을 획득한 경우에만 action을 virtual thread에서 실행합니다. */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(lockName, action) }

    /** [slot]의 lock 이름으로 그룹 슬롯을 획득하고 action을 실행합니다. */
    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(slot, action) }

    /** [slot] 선거 결과를 반환하며 elected 결과의 leader ID를 보존합니다. */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> =
        LeaderFutureBridge.propagateCancellation(
            virtualFuture { delegate.runIfLeaderResult(slot, action) }
        )
}
