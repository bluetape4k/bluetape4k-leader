package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.ExtendOutcome
import kotlinx.coroutines.future.await
import org.redisson.api.RLock
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import java.lang.reflect.Method
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

internal object RedissonOwnerAtomicExtend {

    internal const val WRONG_THREAD_RESULT = -1L
    internal const val NOT_HELD_RESULT = 0L

    private const val OWNER_ATOMIC_EXTEND_SCRIPT = """
        if (redis.call('exists', KEYS[1]) == 0) then
            return 0
        end
        if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then
            return -1
        end
        redis.call('pexpire', KEYS[1], ARGV[2])
        local now = redis.call('time')
        return now[1] * 1000 + math.floor(now[2] / 1000) + tonumber(ARGV[2])
    """

    private val lockNameMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val rawNameMethodCache = ConcurrentHashMap<Class<*>, Method>()

    fun extend(
        redissonClient: RedissonClient,
        lock: RLock,
        acquiringThreadId: Long,
        lockAtMostFor: Duration,
    ): ExtendOutcome {
        val leaseMillis = lockAtMostFor.inWholeMilliseconds.coerceAtLeast(1L)
        val keyName = rawName(lock)
        val result = redissonClient.getScript(StringCodec.INSTANCE).eval<Long>(
            keyName,
            RScript.Mode.READ_WRITE,
            OWNER_ATOMIC_EXTEND_SCRIPT,
            RScript.ReturnType.LONG,
            listOf(keyName),
            ownerField(lock, acquiringThreadId),
            leaseMillis,
        )
        return result.toExtendOutcome()
    }

    suspend fun extendSuspend(
        redissonClient: RedissonClient,
        lock: RLock,
        acquiringThreadId: Long,
        lockAtMostFor: Duration,
    ): ExtendOutcome {
        val leaseMillis = lockAtMostFor.inWholeMilliseconds.coerceAtLeast(1L)
        val keyName = rawName(lock)
        val result = redissonClient.getScript(StringCodec.INSTANCE).evalAsync<Long>(
            keyName,
            RScript.Mode.READ_WRITE,
            OWNER_ATOMIC_EXTEND_SCRIPT,
            RScript.ReturnType.LONG,
            listOf(keyName),
            ownerField(lock, acquiringThreadId),
            leaseMillis,
        )
            .toCompletableFuture()
            .await()
        return result.toExtendOutcome()
    }

    private fun Long.toExtendOutcome(): ExtendOutcome =
        when (this) {
            NOT_HELD_RESULT -> ExtendOutcome.NotHeld
            WRONG_THREAD_RESULT -> ExtendOutcome.WrongThread
            else -> ExtendOutcome.Extended(Instant.ofEpochMilli(this))
        }

    private fun ownerField(lock: RLock, acquiringThreadId: Long): String {
        val method = lockNameMethodCache.computeIfAbsent(lock.javaClass) { type ->
            findMethod(type, "getLockName", java.lang.Long.TYPE)
        }
        return method.invoke(lock, acquiringThreadId) as String
    }

    private fun rawName(lock: RLock): String {
        val method = rawNameMethodCache.computeIfAbsent(lock.javaClass) { type ->
            findMethod(type, "getRawName")
        }
        return method.invoke(lock) as String
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        error("Redisson RLock implementation does not expose $name(${parameterTypes.joinToString()}): ${type.name}")
    }
}
