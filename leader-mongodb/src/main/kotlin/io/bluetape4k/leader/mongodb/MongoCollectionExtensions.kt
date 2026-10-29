package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import org.bson.Document
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection


/** MongoDB 단일 lock 문서를 획득한 경우에만 `action`을 실행하고, 정상 경합이면 `null`을 반환합니다. */
inline fun <T> MongoCollection<Document>.runIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    MongoLeaderElector(this, options).runIfLeader(lockName) { action() }

/** MongoDB 단일 lock 획득과 action future를 `executor`에서 수행하며, 경합이면 완료값 `null`을 반환합니다. */
inline fun <T> MongoCollection<Document>.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    MongoLeaderElector(this, options).runAsyncIfLeader(lockName, executor) { action() }

/** `$lockName:slot:n` 중 하나의 MongoDB group lock을 획득한 경우에만 `action`을 실행합니다. 슬롯이 없으면 `null`입니다. */
inline fun <T> MongoCollection<Document>.runIfLeaderGroup(
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    MongoLeaderGroupElector(this, options)
        .runIfLeader(lockName) { action() }

/** MongoDB 그룹 lock 획득과 action future를 `executor`에서 수행하며, 사용 가능한 슬롯이 없으면 `null`을 반환합니다. */
inline fun <T> MongoCollection<Document>.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    MongoLeaderGroupElector(this, options)
        .runAsyncIfLeader(lockName, executor) { action() }

/** coroutine MongoDB 단일 lock을 획득한 경우에만 suspend `action`을 실행하고, 정상 경합이면 `null`을 반환합니다. */
suspend inline fun <T> CoroutineMongoCollection<Document>.suspendRunIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    MongoSuspendLeaderElector(this, options)
        .runIfLeader(lockName) { action() }


/** 동기·coroutine collection을 같은 namespace로 사용해 MongoDB 그룹 slot을 획득하고, 없으면 `null`을 반환합니다. */
suspend inline fun <T> MongoCollection<Document>.suspendRunIfLeaderGroup(
    coroutineGroupCollection: CoroutineMongoCollection<Document>,
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    MongoSuspendLeaderGroupElector(this, coroutineGroupCollection, options)
        .runIfLeader(lockName) { action() }
