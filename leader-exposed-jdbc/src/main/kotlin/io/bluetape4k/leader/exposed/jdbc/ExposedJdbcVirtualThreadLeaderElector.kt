package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider

/**
 * `ExposedJdbcVirtualThreadLeaderElector`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property delegate Exposed database backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class ExposedJdbcVirtualThreadLeaderElector(
    private val delegate: ExposedJdbcLeaderElector,
) : VirtualThreadLeaderElector,
    LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics {

    /**
     * `선언` 호출은 Exposed database backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}
