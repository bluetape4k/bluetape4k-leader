package io.bluetape4k.leader.redisson

import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.redisson.api.RPermitExpirableSemaphore
import org.redisson.api.RedissonClient
import org.redisson.client.RedisException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Redisson 분산 [RPermitExpirableSemaphore] 를 이용한 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 *
 * - `lockName` 별로 `lg:{lockName}` Redisson [RPermitExpirableSemaphore] 를 사용합니다.
 * - 각 acquire 는 고유 permitId 를 반환하며, release 시 permitId 로 정확한 슬롯을 식별합니다.
 * - `options.minLeaseTime > 0` 이면 빠른 action 종료 시 [RPermitExpirableSemaphore.updateLeaseTime] 으로
 *   slot 의 TTL 을 minLeaseTime 만큼 연장하여 유지합니다 (caller-park 없음).
 * - 클라이언트 crash 시 (release 미호출) leaseTime 만료 후 Redisson 이 자동 회수합니다.
 * - 슬롯 미획득 시 `null` 반환 (ShedLock skip-on-contention).
 *
 * ## [io.bluetape4k.leader.local.LocalLeaderGroupElector] 과의 차이
 * - 이 구현체는 Redis 기반이므로 여러 JVM 프로세스에 걸친 동시 실행 제한에 적합합니다.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3, minLeaseTime = 1.seconds)
 * val election = RedissonLeaderGroupElector(redissonClient, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param redissonClient Redisson 클라이언트
 * @param options 리더 선출 옵션 (maxLeaders, waitTime, leaseTime, minLeaseTime)
 */
class RedissonLeaderGroupElector private constructor(
    private val redissonClient: RedissonClient,
    val options: LeaderGroupElectionOptions,
): LeaderGroupElector {
    companion object: KLogging() {
        @JvmStatic
        operator fun invoke(
            redissonClient: RedissonClient,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): RedissonLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return RedissonLeaderGroupElector(redissonClient, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders

    private val waitTime: Duration = options.waitTime
    private val leaseTime: Duration = options.leaseTime

    /**
     * `lg:{lockName}` 키의 [RPermitExpirableSemaphore] 를 가져옵니다.
     *
     * Codex P1: `trySetPermits(maxLeaders)` 멱등 호출 — 누락 시 0 permits 로 시작하여 acquire 영구 실패.
     */
    private fun getPermitSemaphore(lockName: String): RPermitExpirableSemaphore {
        lockName.requireNotBlank("lockName")
        val semaphore = redissonClient.getPermitExpirableSemaphore("lg:{$lockName}")
        semaphore.trySetPermits(maxLeaders)
        return semaphore
    }

    override fun activeCount(lockName: String): Int =
        maxLeaders - getPermitSemaphore(lockName).availablePermits()

    override fun availableSlots(lockName: String): Int =
        getPermitSemaphore(lockName).availablePermits()

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(
        lockName: String,
        action: () -> T,
    ): T? {
        lockName.requireNotBlank("lockName")
        val semaphore = getPermitSemaphore(lockName)
        log.debug { "리더 그룹 슬롯 획득 요청. lockName=$lockName, maxLeaders=$maxLeaders" }

        val permitId: String? = try {
            semaphore.tryAcquire(
                waitTime.inWholeMilliseconds,
                leaseTime.inWholeMilliseconds,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error(e) { "슬롯 획득 대기 중 인터럽트. lockName=$lockName" }
            throw RedisException("Interrupted while acquiring permit. lockName=$lockName", e)
        }

        if (permitId == null) {
            log.debug { "슬롯 획득 실패. lockName=$lockName" }
            return null
        }
        // Codex P2: acquire 성공 후 startedAtNanos 캡처
        val startedAtNanos = System.nanoTime()
        log.debug { "슬롯 획득 성공. lockName=$lockName, permitId=$permitId" }

        try {
            return action()
        } finally {
            releaseOrExtend(semaphore, permitId, startedAtNanos, lockName)
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        return try {
            lockName.requireNotBlank("lockName")
            val semaphore = getPermitSemaphore(lockName)
            log.debug { "리더 그룹 슬롯 획득 요청 (async). lockName=$lockName" }

            semaphore
                .tryAcquireAsync(
                    waitTime.inWholeMilliseconds,
                    leaseTime.inWholeMilliseconds,
                    TimeUnit.MILLISECONDS,
                )
                .toCompletableFuture()
                .thenComposeAsync({ permitId ->
                    if (permitId == null) {
                        log.debug { "슬롯 획득 실패 (async). lockName=$lockName" }
                        CompletableFuture.completedFuture<T?>(null)
                    } else {
                        // Codex P2: acquire 성공 후 startedAtNanos 캡처
                        val startedAtNanos = System.nanoTime()
                        executeAsync(semaphore, lockName, permitId, startedAtNanos, action)
                    }
                }, executor)
        } catch (e: Throwable) {
            log.error(e) { "Fail to runAsync as Leader Group. lockName=$lockName" }
            failedCompletableFutureOf(e)
        }
    }

    private fun <T> executeAsync(
        semaphore: RPermitExpirableSemaphore,
        lockName: String,
        permitId: String,
        startedAtNanos: Long,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "슬롯 획득 성공 (async). lockName=$lockName, permitId=$permitId" }

        val actionFuture: CompletableFuture<T> = try {
            action()
        } catch (e: Throwable) {
            releaseOrExtendAsync(semaphore, permitId, startedAtNanos, lockName)
            return failedCompletableFutureOf(e)
        }

        return actionFuture.handle<T?> { value, error ->
            releaseOrExtendAsync(semaphore, permitId, startedAtNanos, lockName)
            if (error != null) throw error else value
        }
    }

    private fun releaseOrExtend(
        semaphore: RPermitExpirableSemaphore,
        permitId: String,
        startedAtNanos: Long,
        lockName: String,
    ) {
        try {
            val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
            if (remainingMs > 0) {
                // Codex P2: tryUpdateLeaseTime 미존재. updateLeaseTime 만 존재.
                semaphore.updateLeaseTime(permitId, remainingMs, TimeUnit.MILLISECONDS)
                log.debug {
                    "minLease 유지를 위해 leaseTime 갱신. lockName=$lockName, permitId=$permitId, remainingMs=$remainingMs"
                }
            } else {
                semaphore.release(permitId)
                log.debug { "슬롯 즉시 반납. lockName=$lockName, permitId=$permitId" }
            }
        } catch (e: Throwable) {
            log.warn(e) { "Failed to release/extend permit. lockName=$lockName, permitId=$permitId" }
        }
    }

    private fun releaseOrExtendAsync(
        semaphore: RPermitExpirableSemaphore,
        permitId: String,
        startedAtNanos: Long,
        lockName: String,
    ) {
        val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
        val future = if (remainingMs > 0) {
            semaphore.updateLeaseTimeAsync(permitId, remainingMs, TimeUnit.MILLISECONDS)
        } else {
            semaphore.releaseAsync(permitId)
        }
        future.whenComplete { _, error ->
            if (error != null) {
                log.warn(error) { "Failed to release/extend permit. lockName=$lockName, permitId=$permitId" }
            }
        }
    }
}

/**
 * Redisson 분산 Semaphore를 이용하여 복수 리더 선출을 통한 작업을 수행합니다.
 *
 * ```kotlin
 * val client: RedissonClient = ...
 * val options = LeaderGroupElectionOptions(maxLeaders = 3)
 * val result: Int = client.runIfLeaderGroup("batch-job", options) {
 *     // 최대 3개 프로세스가 동시에 실행
 *     42
 * }
 * ```
 */
inline fun <T> RedissonClient.runIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    lockName.requireNotBlank("lockName")
    return RedissonLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
}
