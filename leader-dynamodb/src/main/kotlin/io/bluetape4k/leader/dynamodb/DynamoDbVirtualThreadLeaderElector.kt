package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import io.bluetape4k.leader.dynamodb.runVirtualIfLeader as currentRunVirtualIfLeader

/**
 * `DynamoDbVirtualThreadLeaderElector`는 DynamoDB blocking elector 호출을 virtual thread에서 실행합니다.
 *
 * @property delegate blocking DynamoDB elector에 lease 획득·action 실행·반납을 위임하는 객체입니다.
 */
class DynamoDbVirtualThreadLeaderElector(
    private val delegate: DynamoDbLeaderElector,
): VirtualThreadLeaderElector,
   LeaderBackendDiagnosticsProvider by DynamoDbLeaderBackendDiagnostics {

    override fun <T> runAsyncIfLeader(lockName: String, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(lockName, action) }

    override fun <T> runAsyncIfLeader(slot: LeaderSlot, action: () -> T): VirtualFuture<T?> =
        virtualFuture { delegate.runIfLeader(slot, action) }

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        action: () -> T,
    ): VirtualFuture<LeaderRunResult<T>> =
        LeaderFutureBridge.propagateCancellation(
            virtualFuture { delegate.runIfLeaderResult(slot, action) }
        )
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runVirtualIfLeader")
fun <T> DynamoDbClient.legacyRunVirtualIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> = this.currentRunVirtualIfLeader(lockName, options, action)
