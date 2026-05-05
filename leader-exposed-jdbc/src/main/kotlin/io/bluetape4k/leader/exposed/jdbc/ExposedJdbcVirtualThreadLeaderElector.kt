package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.VirtualThreadLeaderElector

/**
 * Virtual Thread 기반 Exposed JDBC 리더 선출 구현체.
 *
 * [ExposedJdbcLeaderElector]을 delegate로 사용하며 [virtualFuture]로 래핑합니다.
 * 스키마 초기화는 delegate에서 보장됩니다.
 *
 * ```kotlin
 * val election = ExposedJdbcLeaderElection(db)
 * val vtElection = ExposedJdbcVirtualThreadLeaderElection(election)
 * val result: String? = vtElection.runAsyncIfLeader("job-lock") { processData() }
 *     .get(5, TimeUnit.SECONDS)
 * ```
 *
 * 편의 함수 [Database.runVirtualIfLeader]도 제공됩니다.
 *
 * @property delegate 기반 [ExposedJdbcLeaderElector] 인스턴스
 */
class ExposedJdbcVirtualThreadLeaderElector(
    private val delegate: ExposedJdbcLeaderElector,
) : VirtualThreadLeaderElector {

    /**
     * [lockName]에 대한 리더 선출을 Virtual Thread에서 비동기로 실행합니다.
     *
     * @param lockName 락 식별자
     * @param action 리더 획득 성공 시 실행할 작업
     * @return [action] 실행 결과를 담은 [VirtualFuture]. 리더 획득 실패 시 `null`로 완료됨
     */
    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }
}
