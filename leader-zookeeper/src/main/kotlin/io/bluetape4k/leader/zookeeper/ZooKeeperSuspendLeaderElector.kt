package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessMutex
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Apache Curator [InterProcessMutex] 기반 ZooKeeper suspend 단일 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 * - blocking [ZooKeeperLeaderElector]와 같은 [InterProcessMutex] recipe를 사용해 동일 [lockName]을 상호 배제합니다.
 * - Curator mutex는 thread owner 제약이 있으므로 acquire/release를 호출별 단일 thread dispatcher에서 수행합니다.
 * - 취소 중에도 `withContext(NonCancellable)`에서 lock을 반납하고 [CancellationException]은 재전파합니다.
 * - [LeaderElectionOptions.leaseTime]은 ZooKeeper TTL로 쓰이지 않으며, 세션 종료/만료가 자동 해제 경계입니다.
 *
 * ```kotlin
 * val elector = ZooKeeperSuspendLeaderElector(curator)
 * val result = elector.runIfLeader("sync-job") { syncData() }
 * ```
 *
 * @param client 시작된 [CuratorFramework] 클라이언트. 수명 관리는 호출자 책임입니다.
 * @param basePath 리더 선출 znodes가 생성될 기준 경로
 * @param options 리더 선출 옵션
 */
class ZooKeeperSuspendLeaderElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    private val options: LeaderElectionOptions,
): SuspendLeaderElector {

    companion object: KLoggingChannel() {
        const val DEFAULT_BASE_PATH = "/leader-election"

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            basePath: String = DEFAULT_BASE_PATH,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): ZooKeeperSuspendLeaderElector =
            ZooKeeperSuspendLeaderElector(client, basePath, options)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val mutex = InterProcessMutex(client, path)
        val ownerDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "zookeeper-suspend-leader-$lockName").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

        try {
            log.debug { "ZooKeeper suspend leader lock 획득을 요청합니다. path=$path" }
            val acquired = try {
                runInterruptible(ownerDispatcher) {
                    mutex.acquire(options.waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "ZooKeeper suspend leader lock 획득 실패. path=$path" }
                return null
            }

            if (!acquired) {
                log.debug { "ZooKeeper suspend leader lock 획득 실패 (timeout). path=$path" }
                return null
            }

            log.debug { "ZooKeeper suspend leader lock 획득 성공. path=$path" }
            try {
                return action()
            } finally {
                try {
                    withContext(NonCancellable) {
                        runInterruptible(ownerDispatcher) {
                            mutex.release()
                        }
                    }
                    log.debug { "ZooKeeper suspend leader lock 반납 완료. path=$path" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "ZooKeeper suspend leader lock 반납 실패. path=$path" }
                }
            }
        } finally {
            ownerDispatcher.close()
        }
    }
}

/**
 * ZooKeeper [CuratorFramework]로 suspend 리더 선출 작업을 실행합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeader(
    lockName: String,
    basePath: String = ZooKeeperSuspendLeaderElector.DEFAULT_BASE_PATH,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    ZooKeeperSuspendLeaderElector(this, basePath, options).runIfLeader(lockName) { action() }
