package io.bluetape4k.leader.dynamodb

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.validateLockName
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

import io.bluetape4k.leader.dynamodb.suspendRunIfLeader as currentSuspendRunIfLeader
import io.bluetape4k.leader.dynamodb.suspendRunIfLeaderGroup as currentSuspendRunIfLeaderGroup

/**
 * `선언` 호출은 DynamoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> DynamoDbClient.runIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> T,
): T? {
    lockName.validateLockName()
    return DynamoDbLeaderElector(this, options)
        .runIfLeader(lockName, action)
}

/**
 * `선언` 호출은 DynamoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> DynamoDbClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return DynamoDbLeaderElector(this, options)
        .runAsyncIfLeader(lockName, executor, action)
}


/**
 * `선언` 호출은 DynamoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> DynamoDbClient.runIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? {
    lockName.validateLockName()
    return DynamoDbLeaderGroupElector(this, options)
        .runIfLeader(lockName, action)
}

/**
 * `선언` 호출은 DynamoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> DynamoDbClient.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return DynamoDbLeaderGroupElector(this, options)
        .runAsyncIfLeader(lockName, executor, action)
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
): VirtualFuture<T?> {
    lockName.validateLockName()
    return DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(this, options))
        .runAsyncIfLeader(lockName, action)
}

/**
 * `선언` 호출은 DynamoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lease`, `session`, `TTL`, `owner`, `annotation`, `cleanup` 용어는 backend 계약과 동일하게 유지합니다.
 */
fun <T> DynamoDbClient.runVirtualIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: () -> T,
): VirtualFuture<T?> {
    lockName.validateLockName()
    return DynamoDbVirtualThreadLeaderGroupElector(DynamoDbLeaderGroupElector(this, options))
        .runAsyncIfLeader(lockName, action)
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeader")
suspend fun <T> DynamoDbAsyncClient.legacySuspendRunIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = this.currentSuspendRunIfLeader(lockName, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeaderGroup")
suspend fun <T> DynamoDbAsyncClient.legacySuspendRunIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = this.currentSuspendRunIfLeaderGroup(lockName, options, action)
