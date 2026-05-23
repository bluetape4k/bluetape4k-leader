package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.VirtualThreadLeaderElector
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * Virtual-thread adapter for [DynamoDbLeaderElector].
 *
 * ## Behavior / Contract
 * Runs blocking DynamoDB leadership work in a virtual thread and returns a [VirtualFuture].
 * The delegate keeps the same null-on-skip contract: results are `null` when leadership is not acquired.
 *
 * ```kotlin
 * val elector = DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(dynamoDb))
 * val future = elector.runAsyncIfLeader("nightly-job") { rebuildIndex() }
 * ```
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

/**
 * Runs a blocking action in a virtual thread only while this DynamoDB client holds leadership.
 *
 * ## Behavior / Contract
 * Returns a [VirtualFuture] that completes with the action result when leadership is acquired.
 * The future completes with `null` when another node holds the lock or acquisition times out according
 * to [options].
 *
 * ```kotlin
 * val future = dynamoDb.runVirtualIfLeader("nightly-job") {
 *     rebuildIndex()
 * }
 * ```
 */
fun <T> DynamoDbClient.runVirtualIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> =
    DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(this, options)).runAsyncIfLeader(lockName, action)
