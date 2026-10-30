package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import io.bluetape4k.leader.dynamodb.runVirtualIfLeaderGroup as currentRunVirtualIfLeaderGroup

/**
 * `DynamoDbVirtualThreadLeaderGroupElector`는 DynamoDB group elector 호출을 virtual thread에서 실행합니다.
 *
 * @property delegate blocking DynamoDB group elector에 슬롯 획득·action 실행·반납을 위임하는 객체입니다.
 */
class DynamoDbVirtualThreadLeaderGroupElector(
    private val delegate: DynamoDbLeaderGroupElector,
): VirtualThreadLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by DynamoDbLeaderBackendDiagnostics {

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(lockName)

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(lockName)

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(lockName)

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
@JvmName("runVirtualIfLeaderGroup")
fun <T> DynamoDbClient.legacyRunVirtualIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> = this.currentRunVirtualIfLeaderGroup(lockName, options, action)
