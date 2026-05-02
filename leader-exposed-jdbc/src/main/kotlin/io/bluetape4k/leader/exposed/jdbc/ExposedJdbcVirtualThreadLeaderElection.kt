package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElection

/**
 * Virtual Thread 기반 Exposed JDBC 리더 선출 구현체.
 *
 * [ExposedJdbcLeaderElection]을 delegate로 사용하며, [virtualFuture]로 래핑합니다.
 * delegate가 이미 [ExposedJdbcLeaderElection.invoke]로 ensureSchema를 보장하므로
 * 이 클래스는 직접 생성이 가능합니다 (private constructor 없음).
 *
 * ```kotlin
 * val election = ExposedJdbcLeaderElection(db)
 * val vtElection = ExposedJdbcVirtualThreadLeaderElection(election)
 * val result = vtElection.runAsyncIfLeader("job-lock") { processData() }.await()
 * ```
 *
 * @param delegate 기반 [ExposedJdbcLeaderElection] 인스턴스
 */
class ExposedJdbcVirtualThreadLeaderElection(
    private val delegate: ExposedJdbcLeaderElection,
) : VirtualThreadLeaderElection {

    /**
     * [lockName]에 대한 리더 선출을 Virtual Thread에서 비동기로 실행합니다.
     *
     * delegate의 [ExposedJdbcLeaderElection.runIfLeader]를 Virtual Thread에서 실행합니다.
     *
     * @return [action] 실행 결과를 담은 [VirtualFuture]. 리더 획득 실패 시 `null`로 완료됨
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}
