package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider

/**
 * `ExposedJdbcVirtualThreadLeaderElector`는 JDBC 단일 elector의 blocking 선출을 virtual thread에서 실행합니다.
 *
 * @property delegate JDBC blocking elector에 실제 락 획득·lease·history 처리를 위임하는 객체입니다.
 */
class ExposedJdbcVirtualThreadLeaderElector(
    private val delegate: ExposedJdbcLeaderElector,
): VirtualThreadLeaderElector,
   LeaderBackendDiagnosticsProvider by ExposedJdbcLeaderBackendDiagnostics {

    /**
     * virtual thread에서 [delegate]의 blocking 선거를 실행합니다.
     * 경합으로 락을 얻지 못하면 null을 반환하고, action 예외는 future에 전달합니다.
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}
