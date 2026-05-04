package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.atomicfu.atomic
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Lettuce Redis 클라이언트를 이용한 분산 락(Distributed Lock) 구현체입니다.
 *
 * `SET NX PX` + Lua 스크립트 기반 비재진입(non-reentrant) 분산 뮤텍스입니다.
 * 락 토큰으로 UUID를 사용하여 스레드/코루틴에 독립적으로 동작합니다.
 *
 * ```kotlin
 * val lock = LettuceLock(connection, "my-lock")
 *
 * if (lock.tryLock()) {
 *     try { doWork() } finally { lock.unlock() }
 * }
 * ```
 *
 * @param connection Lettuce StatefulRedisConnection (StringCodec 기반)
 * @param lockKey Redis에 저장될 락 키
 * @param defaultLeaseTime 락 유지 시간 기본값 (기본 30초)
 */
class LettuceLock(
    private val connection: StatefulRedisConnection<String, String>,
    val lockKey: String,
    val defaultLeaseTime: Duration = Duration.ofSeconds(30),
) {
    companion object: KLogging() {
        private const val RETRY_DELAY_MS = 50L
        private const val RETRY_DELAY_NANOS = RETRY_DELAY_MS * 1_000_000L

        private val UNLOCK_SCRIPT = RedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
        )
    }

    private val tokenRef = atomic<String?>(null)

    private val syncCommands: RedisCommands<String, String> = connection.sync()
    private val asyncCommands: RedisAsyncCommands<String, String> = connection.async()

    fun isLocked(): Boolean = syncCommands.get(lockKey) != null

    fun isHeldByCurrentInstance(): Boolean {
        val token = tokenRef.value ?: return false
        return syncCommands.get(lockKey) == token
    }

    // =========================================================================
    // 동기 API
    // =========================================================================

    fun tryLock(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): Boolean {
        val token = Base58.randomString(8)
        val leaseMs = leaseTime.toMillis()
        val deadline = System.currentTimeMillis() + waitTime.toMillis()

        do {
            val args = SetArgs().nx().px(leaseMs)
            val result = syncCommands.set(lockKey, token, args)
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return true
            }
            if (System.currentTimeMillis() < deadline) {
                LockSupport.parkNanos(RETRY_DELAY_NANOS)
            }
        } while (System.currentTimeMillis() < deadline)

        log.debug { "Lock 획득 실패 (timeout): lockKey=$lockKey" }
        return false
    }

    fun lock(leaseTime: Duration = defaultLeaseTime, maxWaitTime: Duration = Duration.ofMinutes(5)) {
        val token = Base58.randomString(length = 8)
        val leaseMs = leaseTime.toMillis()
        val args = SetArgs().nx().px(leaseMs)
        val deadline = System.nanoTime() + maxWaitTime.toNanos()

        while (true) {
            val result = syncCommands.set(lockKey, token, args)
            if (result != null) {
                tokenRef.value = token
                log.debug { "Lock 획득 성공: lockKey=$lockKey" }
                return
            }
            check(System.nanoTime() <= deadline) {
                "Lock 획득 시간 초과: lockKey=$lockKey, maxWaitTime=$maxWaitTime"
            }
            LockSupport.parkNanos(RETRY_DELAY_NANOS)
        }
    }

    fun unlock() {
        val token = tokenRef.getAndSet(null)
            ?: throw IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")

        val released = RedisScriptRunner.run<Long>(
            syncCommands, UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token
        )
        check(released > 0L) {
            "Lock 해제 실패 (토큰 불일치 또는 만료): lockKey=$lockKey"
        }
        log.debug { "Lock 해제 성공: lockKey=$lockKey" }
    }

    // =========================================================================
    // 비동기 API (CompletableFuture)
    // =========================================================================

    fun tryLockAsync(
        waitTime: Duration = Duration.ZERO,
        leaseTime: Duration = defaultLeaseTime,
    ): CompletableFuture<Boolean> {
        val token = Base58.randomString(length = 8)
        val leaseMs = leaseTime.toMillis()
        val deadline = System.currentTimeMillis() + waitTime.toMillis()

        fun attempt(): CompletableFuture<Boolean> {
            val args = SetArgs().nx().px(leaseMs)
            return asyncCommands.set(lockKey, token, args).toCompletableFuture()
                .thenCompose { result ->
                    if (result != null) {
                        tokenRef.value = token
                        log.debug { "Lock 획득 성공 (async): lockKey=$lockKey" }
                        CompletableFuture.completedFuture(true)
                    } else if (System.currentTimeMillis() < deadline) {
                        val delayed = CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    } else {
                        log.debug { "Lock 획득 실패 (timeout, async): lockKey=$lockKey" }
                        CompletableFuture.completedFuture(false)
                    }
                }
        }

        return attempt()
    }

    fun lockAsync(
        leaseTime: Duration = defaultLeaseTime,
        maxWaitTime: Duration = Duration.ofMinutes(5),
    ): CompletableFuture<Unit> {
        val token = Base58.randomString(length = 8)
        val leaseMs = leaseTime.toMillis()
        val deadline = System.currentTimeMillis() + maxWaitTime.toMillis()

        fun attempt(): CompletableFuture<Unit> {
            val args = SetArgs().nx().px(leaseMs)
            return asyncCommands.set(lockKey, token, args).toCompletableFuture()
                .thenCompose { result ->
                    if (result != null) {
                        tokenRef.value = token
                        log.debug { "Lock 획득 성공 (async): lockKey=$lockKey" }
                        CompletableFuture.completedFuture(Unit)
                    } else if (System.currentTimeMillis() < deadline) {
                        val delayed = CompletableFuture.delayedExecutor(RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                        CompletableFuture.runAsync({}, delayed).thenCompose { attempt() }
                    } else {
                        CompletableFuture.failedFuture(
                            IllegalStateException("Lock 획득 시간 초과 (async): lockKey=$lockKey")
                        )
                    }
                }
        }

        return attempt()
    }

    fun unlockAsync(): CompletableFuture<Unit> {
        val token = tokenRef.getAndSet(null)
            ?: return CompletableFuture.failedFuture(
                IllegalStateException("현재 인스턴스가 락을 보유하지 않습니다: lockKey=$lockKey")
            )

        return RedisScriptRunner.runAsync<Long>(
            asyncCommands, UNLOCK_SCRIPT, ScriptOutputType.INTEGER, arrayOf(lockKey), token
        ).thenApply { released ->
            check(released > 0L) {
                "Lock 해제 실패 (토큰 불일치 또는 만료, async): lockKey=$lockKey"
            }
            log.debug { "Lock 해제 성공 (async): lockKey=$lockKey" }
        }
    }
}
