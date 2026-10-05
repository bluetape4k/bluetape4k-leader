package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.dynamodb.runVirtualIfLeader as currentRunVirtualIfLeader
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * `DynamoDbVirtualThreadLeaderElector`는 DynamoDB backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property delegate DynamoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class DynamoDbVirtualThreadLeaderElector(
    private val delegate: DynamoDbLeaderElector,
): VirtualThreadLeaderElector,
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
        LeaderFutureBridge.propagateCancellation(virtualFuture {
            delegate.runIfLeaderResult(slot, action)
        })
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runVirtualIfLeader")
fun <T> DynamoDbClient.legacyRunVirtualIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> = this.currentRunVirtualIfLeader(lockName, options, action)
