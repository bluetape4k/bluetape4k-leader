package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * [HazelcastInstance]의 [IMap] 분산 락을 이용하여 리더 선출을 수행합니다.
 *
 * `putIfAbsent` + TTL 방식의 토큰 기반 락이므로 스레드에 귀속되지 않으며
 * Virtual Thread, ThreadPool 환경에서 모두 안전하게 동작합니다.
 *
 * ```kotlin
 * val election = HazelcastLeaderElector(hazelcastInstance)
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == processData() 반환값 (리더 획득 성공) 또는 null (획득 실패)
 * ```
 *
 * @param hazelcast Hazelcast 클라이언트 인스턴스
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 */
class HazelcastLeaderElector private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
): LeaderElector {

    companion object: KLogging() {
        const val LOCK_MAP_NAME = "bluetape4k:leader:locks"

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): HazelcastLeaderElector = HazelcastLeaderElector(hazelcast, options)
    }

    private val lockMap: IMap<String, String> = hazelcast.getMap(LOCK_MAP_NAME)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = HazelcastLock(lockMap, lockName)
        log.debug { "Leader 승격을 요청합니다 ... lockName=$lockName" }

        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "Leader 승격 실패 (슬롯 없음). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        log.debug { "Leader로 승격하여 작업을 수행합니다. lockName=$lockName" }
        try {
            return action()
        } finally {
            if (lock.isHeldByCurrentInstance()) {
                runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "Leader 권한을 반납했습니다. lockName=$lockName" } }
                    .onFailure { e -> log.error(e) { "Fail to release lock. lockName=$lockName" } }
            }
        }
    }

    /**
     * 락 획득과 action 실행을 [executor] 스레드에서 수행합니다.
     *
     * [IMap] 기반 토큰 락은 스레드 귀속이 없으므로 완료 콜백 스레드에서 안전하게 해제합니다.
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock = HazelcastLock(lockMap, lockName)

        return CompletableFuture
            .supplyAsync({ lock.tryLock(options.waitTime, options.leaseTime) }, executor)
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "Leader 승격 실패 (슬롯 없음, 비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    val acquiredAtNanos = System.nanoTime()
                    log.debug { "Leader로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    val actionFuture = runCatching { action() }
                        .getOrElse { error ->
                            runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                                .onFailure { e -> log.error(e) { "Fail to release lock on action error (async). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.failedFuture(error)
                        }
                    actionFuture.whenCompleteAsync({ _, _ ->
                        if (lock.isHeldByCurrentInstance()) {
                            runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                                .onSuccess { log.debug { "비동기 Leader 권한을 반납했습니다. lockName=$lockName" } }
                                .onFailure { e -> log.error(e) { "Fail to release lock (async). lockName=$lockName" } }
                        }
                    }, executor)
                }
            }, executor)
    }
}

/**
 * Hazelcast 분산 락을 이용하여 리더로 선출된 경우에만 [action]을 실행합니다.
 */
inline fun <T> HazelcastInstance.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    jobName.requireNotBlank("jobName")
    return HazelcastLeaderElector(this, options).runIfLeader(jobName) { action() }
}

/**
 * Hazelcast 분산 락을 이용하여 리더로 선출된 경우에만 비동기 [action]을 실행합니다.
 */
inline fun <T> HazelcastInstance.runAsyncIfLeader(
    jobName: String,
    executor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    jobName.requireNotBlank("jobName")
    return HazelcastLeaderElector(this, options).runAsyncIfLeader(jobName, executor) { action() }
}
