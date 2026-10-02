package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RedissonCandidateRegistryContentionTest: AbstractRedissonLeaderTest() {

    companion object: KLoggingChannel()

    @Test
    fun `blocking and suspend entry lock owners remain mutually exclusive`() = runSuspendIO {
        val lockName = "issue-826-mixed-contention-${System.nanoTime()}"
        val nodeId = "node-826-mixed-contention"
        val cache = redissonClient.getMapCache<String, CandidateInfo>(
            "${RedissonCandidateRegistry.DEFAULT_KEY_PREFIX}:$lockName",
        )
        val entryLock = cache.getLock(nodeId)
        val blockingOwnerId = 1L
        entryLock.lockAsync(blockingOwnerId).await()

        val registry = RedissonCandidateRegistry(redissonClient)

        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            registry.registerCandidateSuspending(
                lockName = lockName,
                info = CandidateInfo(nodeId),
                ttl = Duration.ZERO,
            )
        }.log("Waiting")

        try {
            delay(100.milliseconds)
            waiting.isCompleted.shouldBeFalse()

            entryLock.unlockAsync(blockingOwnerId).await()
            withTimeout(5.seconds) { waiting.await() }
        } finally {
            waiting.cancelAndJoin()
            if (entryLock.isHeldByThread(blockingOwnerId)) {
                entryLock.forceUnlock()
            }
            cache.removeAsync(nodeId).await()
        }
    }
}
