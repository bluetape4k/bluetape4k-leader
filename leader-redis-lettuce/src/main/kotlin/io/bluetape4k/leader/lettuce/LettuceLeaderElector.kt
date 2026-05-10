package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.leader.lettuce.lock.LettuceLock
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * [StatefulRedisConnection]에서 [LettuceLeaderElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val election = connection.leaderElection()
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param options 리더 선출 옵션 (기본값: [LeaderElectionOptions.Default])
 * @return [LettuceLeaderElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.leaderElection(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceLeaderElector = LettuceLeaderElector(this, options)


/**
 * Lettuce Redis 클라이언트를 이용한 리더 선출 구현체입니다.
 *
 * [LettuceLock]을 사용하여 분산 환경에서 단일 리더를 선출합니다.
 * 동기([runIfLeader])와 비동기([runAsyncIfLeader]) 방식을 모두 지원합니다.
 *
 * ```kotlin
 * val election = LettuceLeaderElector(connection)
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (waitTime, leaseTime)
 */
class LettuceLeaderElector(
    private val connection: StatefulRedisConnection<String, String>,
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LeaderElector {

    companion object: KLogging()

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음): lockName=$lockName" }
            return null
        }
        val acquiredAtNanos = System.nanoTime()
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime) {
            lock.extend(options.leaseTime)
        }
        log.debug { "리더 선출 성공: lockName=$lockName" }
        try {
            return action()
        } finally {
            watchdog.close()
            runCatching {
                if (lock.isHeldByCurrentInstance()) {
                    lock.unlock(options.minLeaseTime, acquiredAtNanos)
                }
            }
                .onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        return CompletableFuture.supplyAsync({
            val acquired = lock.tryLock(options.waitTime, options.leaseTime)
            if (!acquired) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
            }
            acquired
        }, executor).thenCompose { acquired ->
            if (!acquired) {
                CompletableFuture.completedFuture(null)
            } else {
                val acquiredAtNanos = System.nanoTime()
                val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime) {
                    lock.extend(options.leaseTime)
                }
                log.debug { "리더 선출 성공 (async): lockName=$lockName" }
                try {
                    action().whenComplete { _, _ ->
                        watchdog.close()
                        runCatching {
                            if (lock.isHeldByCurrentInstance()) {
                                lock.unlock(options.minLeaseTime, acquiredAtNanos)
                            }
                        }
                            .onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
                    }
                } catch (e: Throwable) {
                    watchdog.close()
                    runCatching {
                        if (lock.isHeldByCurrentInstance()) {
                            lock.unlock(options.minLeaseTime, acquiredAtNanos)
                        }
                    }
                        .onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
                    CompletableFuture.failedFuture(e)
                }
            }
        }
    }
}
