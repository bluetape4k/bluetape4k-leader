package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Apache Curator [InterProcessMutex] 기반 ZooKeeper 단일 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 * - [lockName]별 ZooKeeper 경로에 [InterProcessMutex]를 생성하고 [LeaderElectionOptions.waitTime] 동안 획득을 시도합니다.
 * - 획득에 실패하면 [action]을 실행하지 않고 `null`을 반환합니다.
 * - 획득에 성공하면 [action]을 실행하고 `finally`에서 반드시 `release()`를 호출합니다.
 * - Curator recipe 특성상 세션이 끊기면 ZooKeeper ephemeral node가 사라져 락이 자동 해제됩니다.
 *
 * ```kotlin
 * val elector = ZooKeeperLeaderElector(curator)
 * val result = elector.runIfLeader("daily-job") { runJob() }
 * ```
 *
 * @param client 시작된 [CuratorFramework] 클라이언트. 수명 관리는 호출자 책임입니다.
 * @param basePath 리더 선출 znodes가 생성될 기준 경로
 * @param options 리더 선출 옵션
 */
class ZooKeeperLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): LeaderElector {

    companion object: KLogging() {
        const val DEFAULT_BASE_PATH = "/leader-election"

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            basePath: String = DEFAULT_BASE_PATH,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): ZooKeeperLeaderElector =
            ZooKeeperLeaderElector(client, basePath, options)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = InterProcessMutex(client, path)

        log.debug { "ZooKeeper leader lock 획득을 요청합니다. path=$path" }
        val acquired = try {
            mutex.acquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "ZooKeeper leader lock 획득 중 interrupt. path=$path" }
            return null
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper leader lock 획득 실패. path=$path" }
            return null
        }

        if (!acquired) {
            log.debug { "ZooKeeper leader lock 획득 실패 (timeout). path=$path" }
            return null
        }

        log.debug { "ZooKeeper leader lock 획득 성공. path=$path" }
        return try {
            action()
        } finally {
            try {
                mutex.release()
                log.debug { "ZooKeeper leader lock 반납 완료. path=$path" }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn(e) { "ZooKeeper leader lock 반납 중 interrupt. path=$path" }
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper leader lock 반납 실패. path=$path" }
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { runIfLeader(lockName) { action().join() } },
            executor
        )
}

/**
 * ZooKeeper [CuratorFramework]로 리더 선출 작업을 실행합니다.
 */
inline fun <T> CuratorFramework.runIfLeader(
    lockName: String,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderElector(this, basePath, options).runIfLeader(lockName) { action() }

/**
 * ZooKeeper [CuratorFramework]로 비동기 리더 선출 작업을 실행합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeader(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    basePath: String = ZooKeeperLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderElector(this, basePath, options).runAsyncIfLeader(lockName, executor, action)
