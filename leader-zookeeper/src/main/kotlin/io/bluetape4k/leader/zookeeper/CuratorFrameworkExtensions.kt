@file:Suppress("TooManyFunctions") // 실행 변형을 하나의 안정적인 JVM facade로 제공하는 공개 파일입니다.

package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.apache.curator.framework.CuratorFramework
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration


/** 검증된 `path`의 ZooKeeper ephemeral lock을 획득한 경우에만 동기 `action`을 실행하고, 경합이면 `null`을 반환합니다. */
inline fun <T> CuratorFramework.runIfLeader(
    path: ZooKeeperElectionPath,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderElector(this, path.basePath, options).runIfLeader(path.lockName) { action() }

/** `basePath/lockName` 경로를 만들어 단일 ZooKeeper lock을 획득한 경우에만 `action`을 실행합니다. */
inline fun <T> CuratorFramework.runIfLeader(
    lockName: String,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    runIfLeader(ZooKeeperElectionPath(lockName, basePath), options, action)

/** `path`의 ZooKeeper lock 획득과 action future를 `executor`에서 수행하며, 경합이면 완료값 `null`을 반환합니다. */
fun <T> CuratorFramework.runAsyncIfLeader(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderElector(this, path.basePath, options).runAsyncIfLeader(path.lockName, executor, action)

/** `basePath/lockName` 경로의 ZooKeeper lock을 비동기로 기다리고, 경합이면 완료값 `null`을 반환합니다. */
fun <T> CuratorFramework.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    runAsyncIfLeader(ZooKeeperElectionPath(lockName, basePath), executor, options, action)


/** `path`의 ZooKeeper semaphore에서 한 participant lease를 획득한 경우에만 그룹 `action`을 실행합니다. */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? = ZooKeeperLeaderGroupElector(this, options, path.basePath).runIfLeader(path.lockName) { action() }

/** `basePath/lockName` semaphore에서 그룹 슬롯을 획득하고, 사용 가능한 슬롯이 없으면 `null`을 반환합니다. */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: () -> T,
): T? = runIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), options, action)

/** `path`의 ZooKeeper group lease 획득과 action future를 `executor`에서 수행하며, 경합이면 완료값 `null`입니다. */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderGroupElector(this, options, path.basePath).runAsyncIfLeader(path.lockName, executor, action)

/** `basePath/lockName` semaphore의 그룹 lease를 비동기로 획득하고, 슬롯이 없으면 완료값 `null`을 반환합니다. */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = runAsyncIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), executor, options, action)


/** `path`의 ZooKeeper lock을 coroutine에서 획득하고 `finally`에서 elector를 닫습니다. 경합이면 `null`입니다. */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    path: ZooKeeperElectionPath,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    val elector = ZooKeeperSuspendLeaderElector(this, path.basePath, options)
    return try {
        elector.runIfLeader(path.lockName) { action() }
    } finally {
        elector.close()
    }
}

/** `basePath/lockName` ZooKeeper lock을 coroutine에서 획득해 suspend `action`을 실행합니다. */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    lockName: String,
    basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    suspendRunIfLeader(ZooKeeperElectionPath(lockName, basePath), options, action)


/** `path`의 ZooKeeper semaphore lease를 coroutine에서 획득해 그룹 `action`을 실행합니다. */
suspend inline fun <T> CuratorFramework.suspendRunIfLeaderGroup(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    ZooKeeperSuspendLeaderGroupElector(this, options, path.basePath).runIfLeader(path.lockName) { action() }

/** `basePath/lockName` semaphore lease를 coroutine에서 획득하고, 슬롯이 없으면 `null`을 반환합니다. */
suspend inline fun <T> CuratorFramework.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: suspend () -> T,
): T? =
    suspendRunIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), options, action)

fun CuratorFramework.blockUntilConnected(timeout: Duration) =
    blockUntilConnected(timeout.inWholeMilliseconds.toInt(), TimeUnit.MILLISECONDS)
