package io.bluetape4k.leader.redisson

import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.model.Toxic
import eu.rekawek.toxiproxy.model.ToxicDirection
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.testcontainers.storage.RedisServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.testcontainers.containers.Network
import org.testcontainers.toxiproxy.ToxiproxyContainer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Redisson strategic group의 Redis 후보 조회·결과 갱신 I/O 대기 중 취소 회귀입니다.
 *
 * ToxiProxy는 응답을 무기한 보류하고, 테스트는 실제 [kotlinx.coroutines.Job] 취소를
 * 겹쳐서 `CancellationException`이 일반 `FAILURE`로 변환되지 않는지 검증합니다.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RedissonStrategicGroupToxiproxyCancellationTest {

    @Test
    fun `entry lock waiter cancellation cleans up late acquisition and allows reacquisition`() = runSuspendIO {
        withRedisProxy { redis, toxiproxy, proxy ->
            withClients(redis, toxiproxy) { redisson, observerRedisson ->
                val lockName = randomLockName()
                val candidateNode = "toxiproxy-entry-lock-node"
                val elector = RedissonStrategicSuspendLeaderGroupElector(redisson, candidateNode)
                val cache = observerRedisson.getMapCache<String, CandidateInfo>(
                    "${RedissonCandidateRegistry.GROUP_KEY_PREFIX}:$lockName",
                )
                val entryLock = cache.getLock(candidateNode)
                entryLock.lockAsync(OWNER_THREAD_ID).await()

                try {
                    coroutineScope {
                        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                            elector.registerCandidate(lockName, CandidateInfo(candidateNode))
                        }
                        delay(CANCEL_SETTLE_MILLIS)
                        val cancellation = CancellationException("cancel while waiting for entry lock")
                        deferred.cancel(cancellation)
                        val thrown = assertFailsWith<CancellationException> { deferred.await() }
                        thrown.message shouldBeEqualTo cancellation.message
                    }

                    entryLock.unlockAsync(OWNER_THREAD_ID).await()
                    val reacquired = kotlinx.coroutines.withTimeout(5.seconds) {
                        while (true) {
                            if (entryLock.tryLockAsync(REACQUIRE_THREAD_ID).await()) {
                                return@withTimeout true
                            }
                            delay(50)
                        }
                    }
                    reacquired shouldBeEqualTo true
                    entryLock.unlockAsync(REACQUIRE_THREAD_ID).await()
                } finally {
                    if (entryLock.isHeldByThread(OWNER_THREAD_ID)) {
                        entryLock.forceUnlock()
                    }
                    if (entryLock.isHeldByThread(REACQUIRE_THREAD_ID)) {
                        entryLock.forceUnlock()
                    }
                }
            }
        }
    }

    @Test
    fun `후보 조회 응답이 보류된 동안 Job 취소는 action 전에 재전파된다`() = runSuspendIO {
        withRedisProxy { redis, toxiproxy, proxy ->
            withClients(redis, toxiproxy) { redisson, observerRedisson ->
                val lockName = randomLockName()
                val elector = RedissonStrategicSuspendLeaderGroupElector(redisson, NODE_ID)
                val observer = RedissonStrategicSuspendLeaderGroupElector(observerRedisson, NODE_ID)
                elector.registerCandidate(lockName, CandidateInfo(NODE_ID))
                val toxic = proxy.toxics().timeout(
                    "hold-candidate-response",
                    ToxicDirection.DOWNSTREAM,
                    0,
                )

                try {
                    val actionInvoked = CompletableDeferred<Unit>()
                    coroutineScope {
                        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                            elector.runIfLeader(lockName, FifoGroupElectionStrategy) {
                                actionInvoked.complete(Unit)
                                "unexpected"
                            }
                        }
                        delay(CANCEL_SETTLE_MILLIS)
                        deferred.cancel(CancellationException("cancel during candidate lookup"))
                        assertFailsWith<CancellationException> { deferred.await() }
                    }

                    actionInvoked.isCompleted shouldBeEqualTo false
                } finally {
                    removeToxic(toxic)
                }

                val candidate = observer.listCandidates(lockName).single()
                candidate.successCount shouldBeEqualTo 0L
                candidate.failureCount shouldBeEqualTo 0L
            }
        }
    }

    @Test
    fun `결과 갱신 응답이 보류된 동안 Job 취소는 FAILURE로 변환되지 않는다`() = runSuspendIO {
        withRedisProxy { redis, toxiproxy, proxy ->
            withClients(redis, toxiproxy) { redisson, observerRedisson ->
                val lockName = randomLockName()
                val elector = RedissonStrategicSuspendLeaderGroupElector(redisson, NODE_ID)
                val observer = RedissonStrategicSuspendLeaderGroupElector(observerRedisson, NODE_ID)
                elector.registerCandidate(lockName, CandidateInfo(NODE_ID))

                var toxic: Toxic? = null
                try {
                    coroutineScope {
                        val toxicInstalled = CompletableDeferred<Unit>()
                        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                            elector.runIfLeader(lockName, FifoGroupElectionStrategy) {
                                toxic = proxy.toxics().timeout(
                                    "hold-result-response",
                                    ToxicDirection.DOWNSTREAM,
                                    0,
                                )
                                toxicInstalled.complete(Unit)
                                "success-before-cancel"
                            }
                        }
                        toxicInstalled.await()
                        repeat(CANCEL_SETTLE_ROUNDS) {
                            yield()
                            delay(CANCEL_SETTLE_MILLIS / CANCEL_SETTLE_ROUNDS)
                        }
                        deferred.cancel(CancellationException("cancel during result update"))
                        assertFailsWith<CancellationException> { deferred.await() }
                    }
                } finally {
                    removeToxic(toxic)
                }

                val candidate = observer.listCandidates(lockName).single()
                candidate.failureCount shouldBeEqualTo 0L
            }
        }
    }

    private suspend fun <T> withRedisProxy(block: suspend (RedisServer, ToxiproxyContainer, Proxy) -> T): T =
        Network.newNetwork().use { network ->
            RedisServer(reuse = false)
                .withNetwork(network)
                .withNetworkAliases(REDIS_ALIAS)
                .use { redis ->
                    ToxiproxyServer(reuse = false)
                        .withNetwork(network)
                        .use { toxiproxy ->
                            redis.start()
                            toxiproxy.start()
                            val proxy = createRedisProxy(toxiproxy)
                            try {
                                block(redis, toxiproxy, proxy)
                            } finally {
                                runCatching { proxy.delete() }
                            }
                        }
                }
        }

    private suspend fun <T> withClients(
        redis: RedisServer,
        toxiproxy: ToxiproxyContainer,
        block: suspend (RedissonClient, RedissonClient) -> T,
    ): T {
        val operationClient = createRedisson("redis://${toxiproxy.host}:${toxiproxy.getMappedPort(PROXY_PORT)}")
        val observerClient = createRedisson(redis.url)
        return try {
            block(operationClient, observerClient)
        } finally {
            shutdown(operationClient)
            shutdown(observerClient)
        }
    }

    private fun shutdown(client: RedissonClient) {
        runCatching { client.shutdown(0, REDISSON_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

    private fun createRedisson(address: String): RedissonClient =
        Redisson.create(
            RedisServer.Launcher.RedissonLib.getRedissonConfig(
                address = address,
                connectionPoolSize = 8,
                minimumIdleSize = 2,
                threads = 4,
                nettyThreads = 4,
            ).apply {
                useSingleServer().apply {
                    timeout = REDIS_COMMAND_TIMEOUT_MILLIS
                    connectTimeout = REDIS_COMMAND_TIMEOUT_MILLIS
                    retryAttempts = 0
                }
            },
        )

    private fun createRedisProxy(toxiproxy: ToxiproxyContainer): Proxy =
        ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort).createProxy(
            "redis-strategic-${UUID.randomUUID()}",
            "0.0.0.0:$PROXY_PORT",
            "$REDIS_ALIAS:${RedisServer.PORT}",
        )

    private fun removeToxic(toxic: Toxic?) {
        runCatching { toxic?.remove() }
    }

    private fun randomLockName(): String = "toxiproxy:redisson:${UUID.randomUUID()}"

    private companion object {
        const val NODE_ID = "toxiproxy-redisson-node"
        const val REDIS_ALIAS = "redis"
        const val PROXY_PORT = 8666
        const val REDIS_COMMAND_TIMEOUT_MILLIS = 3_000
        const val REDISSON_SHUTDOWN_TIMEOUT_SECONDS = 1L
        const val CANCEL_SETTLE_MILLIS = 250L
        const val CANCEL_SETTLE_ROUNDS = 5
        const val OWNER_THREAD_ID = 826_001L
        const val REACQUIRE_THREAD_ID = 826_002L
    }
}
