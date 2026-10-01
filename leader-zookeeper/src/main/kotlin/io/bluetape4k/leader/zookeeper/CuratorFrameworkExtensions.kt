package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.apache.curator.framework.CuratorFramework
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor


/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> CuratorFramework.runIfLeader(
    path: ZooKeeperElectionPath,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderElector(this, path.basePath, options).runIfLeader(path.lockName) { action() }

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> CuratorFramework.runIfLeader(
    lockName: String,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    runIfLeader(ZooKeeperElectionPath(lockName, basePath), options, action)

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeader(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderElector(this, path.basePath, options).runAsyncIfLeader(path.lockName, executor, action)

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    runAsyncIfLeader(ZooKeeperElectionPath(lockName, basePath), executor, options, action)


/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? = ZooKeeperLeaderGroupElector(this, options, path.basePath).runIfLeader(path.lockName) { action() }

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: () -> T,
): T? = runIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), options, action)

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    path: ZooKeeperElectionPath,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderGroupElector(this, options, path.basePath).runAsyncIfLeader(path.lockName, executor, action)

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = runAsyncIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), executor, options, action)


/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
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

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    lockName: String,
    basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    suspendRunIfLeader(ZooKeeperElectionPath(lockName, basePath), options, action)


/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeaderGroup(
    path: ZooKeeperElectionPath,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    ZooKeeperSuspendLeaderGroupElector(this, options, path.basePath).runIfLeader(path.lockName) { action() }

/**
 * `선언` 호출은 ZooKeeper backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: suspend () -> T,
): T? =
    suspendRunIfLeaderGroup(ZooKeeperElectionPath(lockName, basePath), options, action)
