package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Virtual-thread adapter for [DynamoDbLeaderElector].
 */
class DynamoDbVirtualThreadLeaderElector(
    private val delegate: DynamoDbLeaderElector,
) : VirtualThreadLeaderElector {

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

fun <T> DynamoDbClient.runVirtualIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(this, options)).runAsyncIfLeader(lockName, action)
