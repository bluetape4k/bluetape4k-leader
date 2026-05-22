package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderGroupElector
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Virtual-thread adapter for [DynamoDbLeaderGroupElector].
 */
class DynamoDbVirtualThreadLeaderGroupElector(
    private val delegate: DynamoDbLeaderGroupElector,
) : VirtualThreadLeaderGroupElector {

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(lockName)

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(lockName)

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(lockName)

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

fun <T> DynamoDbClient.runVirtualIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    DynamoDbVirtualThreadLeaderGroupElector(DynamoDbLeaderGroupElector(this, options)).runAsyncIfLeader(lockName, action)
