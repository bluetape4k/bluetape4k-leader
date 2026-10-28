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

/** [this]에서 [lockName]을 획득했을 때만 blocking [action]을 실행하며 경합 시 null을 반환합니다. */
inline fun <T> DynamoDbClient.runIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName()
    return DynamoDbLeaderElector(this, options)
        .runIfLeader(lockName) { action() }
}

/** [executor]에서 [lockName] 획득과 비동기 [action]을 실행하며 경합 시 null로 완료합니다. */
inline fun <T> DynamoDbClient.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return DynamoDbLeaderElector(this, options)
        .runAsyncIfLeader(lockName, executor) { action() }
}


/** [this]의 그룹 슬롯을 획득했을 때만 blocking [action]을 실행하며 사용 가능한 슬롯이 없으면 null입니다. */
inline fun <T> DynamoDbClient.runIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.validateLockName()
    return DynamoDbLeaderGroupElector(this, options)
        .runIfLeader(lockName) { action() }
}

/** [executor]에서 [lockName]의 그룹 슬롯 획득과 비동기 [action]을 실행하며 슬롯 경합 시 null로 완료합니다. */
inline fun <T> DynamoDbClient.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    lockName.validateLockName()
    return DynamoDbLeaderGroupElector(this, options)
        .runAsyncIfLeader(lockName, executor) { action() }
}

/** virtual thread에서 [lockName]을 획득하고 blocking [action]을 실행하는 future를 반환합니다. */
inline fun <T> DynamoDbClient.runVirtualIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    crossinline action: () -> T,
): VirtualFuture<T?> {
    lockName.validateLockName()
    return DynamoDbVirtualThreadLeaderElector(DynamoDbLeaderElector(this, options))
        .runAsyncIfLeader(lockName) { action() }
}

/** virtual thread에서 [lockName]의 그룹 슬롯을 획득하고 [action]을 실행하는 future를 반환합니다. */
inline fun <T> DynamoDbClient.runVirtualIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): VirtualFuture<T?> {
    lockName.validateLockName()
    return DynamoDbVirtualThreadLeaderGroupElector(DynamoDbLeaderGroupElector(this, options))
        .runAsyncIfLeader(lockName) { action() }
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeader")
suspend inline fun <T> DynamoDbAsyncClient.legacySuspendRunIfLeader(
    lockName: String,
    options: DynamoDbLeaderElectionOptions = DynamoDbLeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeader(lockName, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeaderGroup")
suspend inline fun <T> DynamoDbAsyncClient.legacySuspendRunIfLeaderGroup(
    lockName: String,
    options: DynamoDbLeaderGroupElectionOptions = DynamoDbLeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeaderGroup(lockName, options, action)
