package io.bluetape4k.leader.zookeeper

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2
import org.apache.curator.framework.recipes.locks.Lease
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Apache Curator [InterProcessSemaphoreV2] 기반 ZooKeeper 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 * - 동일 [lockName]에 대해 최대 [maxLeaders]개의 lease만 동시에 허용합니다.
 * - lease 획득에 실패하면 [action]을 실행하지 않고 `null`을 반환합니다.
 * - 획득한 [Lease]는 `finally`에서 반드시 `close()`합니다.
 *
 * ```kotlin
 * val elector = ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param client 시작된 [CuratorFramework] 클라이언트. 수명 관리는 호출자 책임입니다.
 * @param basePath 리더 그룹 znodes가 생성될 기준 경로
 * @param options 리더 그룹 선출 옵션
 */
class ZooKeeperLeaderGroupElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    options: LeaderGroupElectionOptions,
): LeaderGroupElector {

    companion object: KLogging() {
        const val DEFAULT_BASE_PATH = "/leader-group-election"

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
            basePath: String = DEFAULT_BASE_PATH,
        ): ZooKeeperLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return ZooKeeperLeaderGroupElector(client, basePath, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime = options.waitTime

    override fun activeCount(lockName: String): Int {
        val semaphore = semaphore(lockName)
        return try {
            semaphore.participantNodes.size.coerceAtMost(maxLeaders)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group participant 조회 실패. lockName=$lockName" }
            0
        }
    }

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val semaphore = InterProcessSemaphoreV2(client, path, maxLeaders)

        log.debug { "ZooKeeper group lease 획득을 요청합니다. path=$path, maxLeaders=$maxLeaders" }
        val lease = try {
            semaphore.acquire(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "ZooKeeper group lease 획득 중 interrupt. path=$path" }
            return null
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper group lease 획득 실패. path=$path" }
            return null
        } ?: return null

        log.debug { "ZooKeeper group lease 획득 성공. path=$path" }
        return try {
            action()
        } finally {
            try {
                lease.close()
                log.debug { "ZooKeeper group lease 반납 완료. path=$path" }
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper group lease 반납 실패. path=$path" }
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

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/**
 * ZooKeeper [CuratorFramework]로 복수 리더 선출 작업을 실행합니다.
 */
inline fun <T> CuratorFramework.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: () -> T,
): T? =
    ZooKeeperLeaderGroupElector(this, options, basePath).runIfLeader(lockName) { action() }

/**
 * ZooKeeper [CuratorFramework]로 비동기 복수 리더 선출 작업을 실행합니다.
 */
fun <T> CuratorFramework.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperLeaderGroupElector.DEFAULT_BASE_PATH,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    ZooKeeperLeaderGroupElector(this, options, basePath).runAsyncIfLeader(lockName, executor, action)
