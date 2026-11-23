package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider

/**
 * `ExposedJdbcVirtualThreadLeaderGroupElector`는 JDBC 그룹 elector의 blocking 선거를 virtual thread에서 실행합니다.
 *
 * lock-name overload는 경합 시 `null`을 반환하고, [LeaderSlot] overload는 결과의 `leaderId`를 보존합니다.
 * 반환된 future를 취소하면 실행 중인 action에 interruption을 전달하고 획득한 슬롯을 반납합니다.
 *
 * @property delegate 슬롯 획득·lease·history·반납을 담당하는 Exposed JDBC blocking elector입니다.
 */
class ExposedJdbcVirtualThreadLeaderGroupElector(
    private val delegate: ExposedJdbcLeaderGroupElector,
): VirtualThreadLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics {

    /**
     * lock 이름으로 그룹 슬롯을 획득한 경우에만 action을 virtual thread에서 실행합니다.
     *
     * @param lockName 그룹 선거에 사용하는 lock 이름입니다.
     * @param action 슬롯을 획득한 경우 실행할 작업입니다.
     * @return 작업 결과를 담은 future이며 슬롯을 얻지 못하면 `null`로 완료됩니다.
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        action: () -> T,
    ): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(lockName, action) }

    /**
     * [slot]의 lock 이름으로 슬롯을 획득하고 action을 실행합니다.
     * 이 overload는 기본 bridge를 거치지 않아 slot의 호출 경로가 명시적으로 유지됩니다.
     */
    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(slot.lockName, action) }

    /**
     * [slot] 선거 결과를 실행 상태와 leader ID가 포함된 [LeaderRunResult]로 반환합니다.
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> =
        virtualFuture {
            when (val result = delegate.runIfLeaderResult(slot.lockName, action)) {
                is LeaderRunResult.Elected -> LeaderRunResult.Elected(result.value, leaderId = slot.leaderId)
                else -> result
            }
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
