package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * `DynamoDbVirtualThreadLeaderElector`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property delegate DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbVirtualThreadLeaderElector(
    private val delegate: DynamoDbLeaderElector,
) : VirtualThreadLeaderElector,
    LeaderBackendDiagnosticsProvider by DynamoDbLeaderBackendDiagnostics {

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(lockName, action)
        }

    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture {
            delegate.runIfLeader(slot, action)
        }

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> =
        virtualFuture {
            delegate.runIfLeaderResult(slot, action)
    }
}

/**
 * `선언` 호출은 DynamoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> DynamoDbClient.runVirtualIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(this, options)).runAsyncIfLeader(lockName, action)
