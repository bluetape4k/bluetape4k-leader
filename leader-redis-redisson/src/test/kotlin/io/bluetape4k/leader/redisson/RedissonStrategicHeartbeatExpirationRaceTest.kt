package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.redisson.client.codec.Codec
import org.redisson.misc.CompletableFutureWrapper
import kotlin.time.Duration.Companion.seconds

class RedissonStrategicHeartbeatExpirationRaceTest : AbstractRedissonLeaderTest() {

    @Test
    fun `blocking single refresh does not resurrect candidate after read expires`() = runSuspendIO {
        assertRefreshDoesNotResurrectExpiredCandidate(gateAsyncRead = false) { client, lockName, stale, fresh ->
            withContext(Dispatchers.IO) {
                RedissonStrategicLeaderElector(client, stale.nodeId)
                    .refreshCandidate(lockName, fresh, ttl = 2.seconds)
            }
        }
    }

    @Test
    fun `blocking group refresh does not resurrect candidate after read expires`() = runSuspendIO {
        assertRefreshDoesNotResurrectExpiredCandidate(
            keyPrefix = RedissonCandidateRegistry.GROUP_KEY_PREFIX,
            gateAsyncRead = false,
        ) { client, lockName, stale, fresh ->
            withContext(Dispatchers.IO) {
                RedissonStrategicLeaderGroupElector(client, stale.nodeId)
                    .refreshCandidate(lockName, fresh, ttl = 2.seconds)
            }
        }
    }

    @Test
    fun `suspend single refresh does not resurrect candidate after read expires`() = runSuspendIO {
        assertRefreshDoesNotResurrectExpiredCandidate(gateAsyncRead = false) { client, lockName, stale, fresh ->
            RedissonStrategicSuspendLeaderElector(client, stale.nodeId)
                .refreshCandidate(lockName, fresh, ttl = 2.seconds)
        }
    }

    @Test
    fun `suspend group refresh does not resurrect candidate after read expires`() = runSuspendIO {
        assertRefreshDoesNotResurrectExpiredCandidate(
            keyPrefix = RedissonCandidateRegistry.GROUP_KEY_PREFIX,
            gateAsyncRead = true,
        ) { client, lockName, stale, fresh ->
            RedissonStrategicSuspendLeaderGroupElector(client, stale.nodeId)
                .refreshCandidate(lockName, fresh, ttl = 2.seconds)
        }
    }

    private suspend fun assertRefreshDoesNotResurrectExpiredCandidate(
        keyPrefix: String = RedissonCandidateRegistry.DEFAULT_KEY_PREFIX,
        gateAsyncRead: Boolean,
        refresh: suspend (
            client: RedissonClient,
            lockName: String,
            stale: CandidateInfo,
            fresh: CandidateInfo,
        ) -> Unit,
    ) {
        val lockName = randomName()
        val nodeId = "node-expiration-race"
        val stale = CandidateInfo(nodeId = nodeId, metadata = mapOf("source" to "stale"))
        val fresh = stale.copy(metadata = mapOf("source" to "fresh"))
        val cacheName = "$keyPrefix:$lockName"
        val realCache = redissonClient.getMapCache<String, CandidateInfo>(cacheName)
        val cache = spyk(realCache)
        val client = mockk<RedissonClient>()
        val operationEntered = CompletableDeferred<Unit>()
        val releaseOperation = CompletableDeferred<Unit>()

        realCache.put(nodeId, stale, 2.seconds.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
        every { client.getMapCache<String, CandidateInfo>(cacheName) } returns cache
        every { client.getScript(any<Codec>()) } answers {
            redissonClient.getScript(firstArg<Codec>())
        }
        if (gateAsyncRead) {
            every { cache.getAsync(nodeId) } answers {
                val source = callOriginal()
                val gated = java.util.concurrent.CompletableFuture<CandidateInfo>()
                source.whenComplete { value, failure ->
                    if (failure != null) {
                        gated.completeExceptionally(failure)
                    } else {
                        operationEntered.complete(Unit)
                        releaseOperation.invokeOnCompletion { gated.complete(value) }
                    }
                }
                CompletableFutureWrapper(gated)
            }
        } else {
            every { cache.get(nodeId) } answers {
                val value = callOriginal()
                operationEntered.complete(Unit)
                runBlocking { releaseOperation.await() }
                value
            }
        }

        try {
            coroutineScope {
                val refreshJob = async(Dispatchers.IO) {
                    refresh(client, lockName, stale, fresh)
                }

                withTimeout(2.seconds) { operationEntered.await() }
                withTimeout(5.seconds) {
                    while (realCache.remainTimeToLive(nodeId) > 0L) {
                        delay(10)
                    }
                }
                releaseOperation.complete(Unit)
                refreshJob.await()

                realCache.get(nodeId).shouldBeNull()
                realCache.readAllValues().shouldBeEmpty()
            }
        } finally {
            releaseOperation.complete(Unit)
            realCache.removeAsync(nodeId).toCompletableFuture().join()
        }
    }
}
