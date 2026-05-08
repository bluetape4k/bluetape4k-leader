package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2
import java.util.concurrent.TimeUnit

/**
 * Apache Curator [InterProcessSemaphoreV2] 기반 ZooKeeper suspend 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 * - 동일 [lockName]에 대해 최대 [maxLeaders]개의 lease만 동시에 허용합니다.
 * - lease 획득에 실패하면 [action]을 실행하지 않고 `null`을 반환합니다.
 * - 취소 중에도 `withContext(NonCancellable)`에서 lease를 반납하고 [CancellationException]은 재전파합니다.
 *
 * ```kotlin
 * val elector = ZooKeeperSuspendLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param client 시작된 [CuratorFramework] 클라이언트. 수명 관리는 호출자 책임입니다.
 * @param basePath 리더 그룹 znodes가 생성될 기준 경로
 * @param options 리더 그룹 선출 옵션
 */
class ZooKeeperSuspendLeaderGroupElector private constructor(
    private val client: CuratorFramework,
    private val basePath: String,
    options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector {

    companion object: KLoggingChannel() {
        const val DEFAULT_BASE_PATH = "/leader-group-election"

        @JvmStatic
        operator fun invoke(
            client: CuratorFramework,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
            basePath: String = DEFAULT_BASE_PATH,
        ): ZooKeeperSuspendLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return ZooKeeperSuspendLeaderGroupElector(client, basePath, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime = options.waitTime

    override fun activeCount(lockName: String): Int {
        val semaphore = semaphore(lockName)
        return try {
            semaphore.participantNodes.size.coerceAtMost(maxLeaders)
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper suspend group participant 조회 실패. lockName=$lockName" }
            0
        }
    }

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val path = ZooKeeperPaths.electionPath(basePath, lockName)
        val semaphore = InterProcessSemaphoreV2(client, path, maxLeaders)

        log.debug { "ZooKeeper suspend group lease 획득을 요청합니다. path=$path, maxLeaders=$maxLeaders" }
        val lease = try {
            withContext(Dispatchers.IO) {
                semaphore.acquire(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "ZooKeeper suspend group lease 획득 실패. path=$path" }
            return null
        } ?: return null

        log.debug { "ZooKeeper suspend group lease 획득 성공. path=$path" }
        try {
            return action()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    lease.close()
                    log.debug { "ZooKeeper suspend group lease 반납 완료. path=$path" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "ZooKeeper suspend group lease 반납 실패. path=$path" }
                }
            }
        }
    }

    private fun semaphore(lockName: String): InterProcessSemaphoreV2 =
        InterProcessSemaphoreV2(client, ZooKeeperPaths.electionPath(basePath, lockName), maxLeaders)
}

/**
 * ZooKeeper [CuratorFramework]로 suspend 복수 리더 선출 작업을 실행합니다.
 */
suspend inline fun <T> CuratorFramework.suspendRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    basePath: String = ZooKeeperSuspendLeaderGroupElector.DEFAULT_BASE_PATH,
    crossinline action: suspend () -> T,
): T? =
    ZooKeeperSuspendLeaderGroupElector(this, options, basePath).runIfLeader(lockName) { action() }
