package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 외부 backend의 strategic candidate registry 계약을 검증하는 공통 JUnit 5 fixture입니다.
 *
 * 하위 테스트는 [createProvider]만 구현합니다. fixture는 blocking/suspend와 single/group 조합을
 * 모두 검증하고 각 test가 만든 provider를 `finally`에서 정확히 한 번 닫습니다. 이 fixture의
 * 통과는 후보 저장 의미를 증명하며 backend 성능, provisioning, retry, 장애 복구를 보증하지
 * 않습니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractStrategicBackendConformanceTest {

    protected abstract fun createProvider(): StrategicBackendConformanceProvider

    @Test
    fun `blocking lifecycle은 refresh 상태 보존과 idempotent cleanup을 보장한다`() = withProvider { provider ->
        StrategicBackendKind.entries.forEach { kind ->
            val lockName = lockName("blocking-lifecycle", kind)
            val backend = provider.blocking(kind, NODE_A)
            val original = candidate(NODE_A)

            backend.refreshCandidate(lockName, original, REFRESH_TTL)
            backend.listCandidates(lockName).shouldBeEmpty()
            backend.registerCandidate(lockName, original, INITIAL_TTL)
            backend.refreshCandidate(lockName, original.copy(metadata = NEW_METADATA), REFRESH_TTL)
            assertRefreshPreserved(backend.listCandidates(lockName).single(), original)
            backend.updateResult(lockName, NODE_A, CandidateResult.SUCCESS)
            assertResultUpdated(backend.listCandidates(lockName).single(), original)

            backend.unregisterCandidate(lockName, NODE_A)
            backend.unregisterCandidate(lockName, NODE_A)
            backend.listCandidates(lockName).shouldBeEmpty()
        }
    }

    @Test
    fun `suspend lifecycle은 refresh 상태 보존과 idempotent cleanup을 보장한다`() = runSuspendIO {
        withProviderSuspend { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                val lockName = lockName("suspend-lifecycle", kind)
                val backend = provider.suspending(kind, NODE_A)
                val original = candidate(NODE_A)

                backend.refreshCandidate(lockName, original, REFRESH_TTL)
                backend.listCandidates(lockName).shouldBeEmpty()
                backend.registerCandidate(lockName, original, INITIAL_TTL)
                backend.refreshCandidate(lockName, original.copy(metadata = NEW_METADATA), REFRESH_TTL)
                assertRefreshPreserved(backend.listCandidates(lockName).single(), original)
                backend.updateResult(lockName, NODE_A, CandidateResult.SUCCESS)
                assertResultUpdated(backend.listCandidates(lockName).single(), original)

                backend.unregisterCandidate(lockName, NODE_A)
                backend.unregisterCandidate(lockName, NODE_A)
                backend.listCandidates(lockName).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `blocking expired refresh는 후보를 부활시키지 않는다`() = withProvider { provider ->
        StrategicBackendKind.entries.forEach { kind ->
            val lockName = lockName("blocking-expiry", kind)
            val backend = provider.blocking(kind, NODE_A)
            backend.registerCandidate(lockName, candidate(NODE_A), EXPIRING_TTL)

            provider.awaitCandidateExpiration(kind, lockName, NODE_A, EXPIRY_TIMEOUT).shouldBeTrue()
            backend.listCandidates(lockName).shouldBeEmpty()
            backend.refreshCandidate(lockName, candidate(NODE_A).copy(metadata = NEW_METADATA), REFRESH_TTL)
            backend.listCandidates(lockName).shouldBeEmpty()
        }
    }

    @Test
    fun `suspend expired refresh는 후보를 부활시키지 않는다`() = runSuspendIO {
        withProviderSuspend { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                val lockName = lockName("suspend-expiry", kind)
                val backend = provider.suspending(kind, NODE_A)
                backend.registerCandidate(lockName, candidate(NODE_A), EXPIRING_TTL)

                provider.awaitCandidateExpiration(kind, lockName, NODE_A, EXPIRY_TIMEOUT).shouldBeTrue()
                backend.listCandidates(lockName).shouldBeEmpty()
                backend.refreshCandidate(lockName, candidate(NODE_A).copy(metadata = NEW_METADATA), REFRESH_TTL)
                backend.listCandidates(lockName).shouldBeEmpty()
            }
        }
    }

    @Test
    fun `blocking winner만 action을 정확히 한 번 실행하고 loser는 null을 반환한다`() = withProvider { provider ->
        StrategicBackendKind.entries.forEach { kind ->
            val lockName = lockName("blocking-winner", kind)
            val winner = provider.blocking(kind, NODE_A)
            val loser = provider.blocking(kind, NODE_B)
            val winnerActions = AtomicInteger()
            val loserActions = AtomicInteger()

            winner.registerCandidate(lockName, candidate(NODE_A), INITIAL_TTL)
            loser.registerCandidate(lockName, candidate(NODE_B, LATER), INITIAL_TTL)
            winner.runIfLeader(lockName) {
                winnerActions.incrementAndGet()
                "winner"
            } shouldBeEqualTo "winner"
            loser.runIfLeader(lockName) {
                loserActions.incrementAndGet()
                "loser"
            }.shouldBeNull()

            winnerActions.get() shouldBeEqualTo 1
            loserActions.get() shouldBeEqualTo 0
            val candidates = winner.listCandidates(lockName).associateBy(CandidateInfo::nodeId)
            checkNotNull(candidates[NODE_A]).successCount shouldBeEqualTo 8L
            checkNotNull(candidates[NODE_B]) shouldBeEqualTo candidate(NODE_B, LATER)
        }
    }

    @Test
    fun `suspend winner만 action을 정확히 한 번 실행하고 loser는 null을 반환한다`() = runSuspendIO {
        withProviderSuspend { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                val lockName = lockName("suspend-winner", kind)
                val winner = provider.suspending(kind, NODE_A)
                val loser = provider.suspending(kind, NODE_B)
                val winnerActions = AtomicInteger()
                val loserActions = AtomicInteger()

                winner.registerCandidate(lockName, candidate(NODE_A), INITIAL_TTL)
                loser.registerCandidate(lockName, candidate(NODE_B, LATER), INITIAL_TTL)
                winner.runIfLeader(lockName) {
                    winnerActions.incrementAndGet()
                    "winner"
                } shouldBeEqualTo "winner"
                loser.runIfLeader(lockName) {
                    loserActions.incrementAndGet()
                    "loser"
                }.shouldBeNull()

                winnerActions.get() shouldBeEqualTo 1
                loserActions.get() shouldBeEqualTo 0
                val candidates = winner.listCandidates(lockName).associateBy(CandidateInfo::nodeId)
                checkNotNull(candidates[NODE_A]).successCount shouldBeEqualTo 8L
                checkNotNull(candidates[NODE_B]) shouldBeEqualTo candidate(NODE_B, LATER)
            }
        }
    }

    @Test
    fun `suspend cancellation은 실패 결과로 기록하지 않고 재전파한다`() = runSuspendIO {
        withProviderSuspend { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                val lockName = lockName("suspend-cancellation", kind)
                val backend = provider.suspending(kind, NODE_A)
                val original = candidate(NODE_A)
                backend.registerCandidate(lockName, original, INITIAL_TTL)

                assertFailsWith<CancellationException> {
                    backend.runIfLeader(lockName) { throw CancellationException("cancelled") }
                }

                backend.listCandidates(lockName).single() shouldBeEqualTo original
            }
        }
    }

    @Test
    fun `blocking concurrent register refresh update는 torn write와 lost update를 만들지 않는다`() =
        withProvider { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                val registerLock = lockName("blocking-register", kind)
                val updateLock = lockName("blocking-update", kind)
                val backend = provider.blocking(kind, NODE_A)
                val sequence = AtomicInteger()
                val registered = java.util.concurrent.ConcurrentLinkedQueue<CandidateInfo>()

                MultithreadingTester().workers(WORKERS).rounds(ROUNDS).add {
                    val n = sequence.incrementAndGet().toLong()
                    val info = candidate(NODE_A).copy(
                        successCount = n,
                        failureCount = n * 2,
                        metadata = mapOf("sequence" to n.toString()),
                    )
                    registered.add(info)
                    backend.registerCandidate(registerLock, info, INITIAL_TTL)
                }.run()
                registered.contains(backend.listCandidates(registerLock).single()).shouldBeTrue()

                backend.registerCandidate(updateLock, candidate(NODE_A).copy(successCount = 0L), INITIAL_TTL)
                MultithreadingTester().workers(WORKERS).rounds(ROUNDS).add {
                    backend.updateResult(updateLock, NODE_A, CandidateResult.SUCCESS)
                    backend.refreshCandidate(updateLock, candidate(NODE_A).copy(metadata = NEW_METADATA), REFRESH_TTL)
                }.run()

                val updated = backend.listCandidates(updateLock).single()
                updated.successCount shouldBeEqualTo (WORKERS * ROUNDS).toLong()
                updated.metadata shouldBeEqualTo NEW_METADATA
            }
        }

    @Test
    fun `suspend concurrent register refresh update는 torn write와 lost update를 만들지 않는다`() = runSuspendIO {
        withProviderSuspend { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                val registerLock = lockName("suspend-register", kind)
                val updateLock = lockName("suspend-update", kind)
                val backend = provider.suspending(kind, NODE_A)
                val sequence = AtomicInteger()
                val registered = java.util.concurrent.ConcurrentLinkedQueue<CandidateInfo>()

                coroutineScope {
                    List(WORKERS) {
                        async(Dispatchers.Default) {
                            repeat(ROUNDS) {
                                val n = sequence.incrementAndGet().toLong()
                                val info = candidate(NODE_A).copy(
                                    successCount = n,
                                    failureCount = n * 2,
                                    metadata = mapOf("sequence" to n.toString()),
                                )
                                registered.add(info)
                                backend.registerCandidate(registerLock, info, INITIAL_TTL)
                            }
                        }
                    }.awaitAll()
                }
                registered.contains(backend.listCandidates(registerLock).single()).shouldBeTrue()

                backend.registerCandidate(updateLock, candidate(NODE_A).copy(successCount = 0L), INITIAL_TTL)
                coroutineScope {
                    List(WORKERS) {
                        async(Dispatchers.Default) {
                            repeat(ROUNDS) {
                                backend.updateResult(updateLock, NODE_A, CandidateResult.SUCCESS)
                                backend.refreshCandidate(
                                    updateLock,
                                    candidate(NODE_A).copy(metadata = NEW_METADATA),
                                    REFRESH_TTL,
                                )
                            }
                        }
                    }.awaitAll()
                }

                val updated = backend.listCandidates(updateLock).single()
                updated.successCount shouldBeEqualTo (WORKERS * ROUNDS).toLong()
                updated.metadata shouldBeEqualTo NEW_METADATA
            }
        }
    }

    @Test
    fun `blocking concurrent refresh와 unregister는 후보를 부활시키지 않는다`() = withProvider { provider ->
        val executor = Executors.newFixedThreadPool(2)
        try {
            StrategicBackendKind.entries.forEach { kind ->
                repeat(RACE_ROUNDS) { round ->
                    val lockName = lockName("blocking-unregister-$round", kind)
                    val backend = provider.blocking(kind, NODE_A)
                    val gate = CountDownLatch(1)
                    backend.registerCandidate(lockName, candidate(NODE_A), INITIAL_TTL)

                    val refresh = executor.submit {
                        gate.await()
                        backend.refreshCandidate(lockName, candidate(NODE_A).copy(metadata = NEW_METADATA), REFRESH_TTL)
                    }
                    val unregister = executor.submit {
                        gate.await()
                        backend.unregisterCandidate(lockName, NODE_A)
                    }
                    gate.countDown()
                    refresh.get()
                    unregister.get()
                    backend.listCandidates(lockName).shouldBeEmpty()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `suspend concurrent refresh와 unregister는 후보를 부활시키지 않는다`() = runSuspendIO {
        withProviderSuspend { provider ->
            StrategicBackendKind.entries.forEach { kind ->
                repeat(RACE_ROUNDS) { round ->
                    val lockName = lockName("suspend-unregister-$round", kind)
                    val backend = provider.suspending(kind, NODE_A)
                    val gate = CompletableDeferred<Unit>()
                    backend.registerCandidate(lockName, candidate(NODE_A), INITIAL_TTL)

                    coroutineScope {
                        val refresh = async(Dispatchers.Default) {
                            gate.await()
                            backend.refreshCandidate(
                                lockName,
                                candidate(NODE_A).copy(metadata = NEW_METADATA),
                                REFRESH_TTL,
                            )
                        }
                        val unregister = async(Dispatchers.Default) {
                            gate.await()
                            backend.unregisterCandidate(lockName, NODE_A)
                        }
                        gate.complete(Unit)
                        awaitAll(refresh, unregister)
                    }
                    backend.listCandidates(lockName).shouldBeEmpty()
                }
            }
        }
    }

    private fun assertRefreshPreserved(actual: CandidateInfo, original: CandidateInfo) {
        actual.registeredAt shouldBeEqualTo original.registeredAt
        actual.lastStartTime shouldBeEqualTo original.lastStartTime
        actual.lastCompletionTime shouldBeEqualTo original.lastCompletionTime
        actual.successCount shouldBeEqualTo original.successCount
        actual.failureCount shouldBeEqualTo original.failureCount
        actual.metadata shouldBeEqualTo NEW_METADATA
    }

    private fun assertResultUpdated(actual: CandidateInfo, original: CandidateInfo) {
        actual.registeredAt shouldBeEqualTo original.registeredAt
        actual.lastStartTime shouldBeEqualTo original.lastStartTime
        actual.successCount shouldBeEqualTo original.successCount + 1
        actual.failureCount shouldBeEqualTo original.failureCount
        actual.metadata shouldBeEqualTo NEW_METADATA
    }

    private fun candidate(nodeId: String, registeredAt: Instant = REGISTERED_AT) = CandidateInfo(
        nodeId = nodeId,
        registeredAt = registeredAt,
        lastStartTime = LAST_START,
        lastCompletionTime = LAST_COMPLETION,
        successCount = 7L,
        failureCount = 2L,
        metadata = OLD_METADATA,
    )

    private fun lockName(prefix: String, kind: StrategicBackendKind): String =
        "$prefix-${kind.name.lowercase()}-${Base58.randomString(8).lowercase()}"

    private inline fun <T> withProvider(block: (StrategicBackendConformanceProvider) -> T): T {
        val provider = createProvider()
        return try {
            block(provider)
        } finally {
            provider.close()
        }
    }

    private suspend fun <T> withProviderSuspend(
        block: suspend (StrategicBackendConformanceProvider) -> T,
    ): T {
        val provider = createProvider()
        return try {
            block(provider)
        } finally {
            provider.close()
        }
    }

    private companion object {
        const val NODE_A = "node-a"
        const val NODE_B = "node-b"
        const val WORKERS = 8
        const val ROUNDS = 25
        const val RACE_ROUNDS = 25
        val INITIAL_TTL = 30.seconds
        val REFRESH_TTL = 60.seconds
        val EXPIRING_TTL = 100.milliseconds
        val EXPIRY_TIMEOUT = 5.seconds
        val REGISTERED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val LATER: Instant = Instant.parse("2026-01-01T00:00:01Z")
        val LAST_START: Instant = Instant.parse("2026-01-01T00:01:00Z")
        val LAST_COMPLETION: Instant = Instant.parse("2026-01-01T00:02:00Z")
        val OLD_METADATA = mapOf("version" to "old")
        val NEW_METADATA = mapOf("version" to "new")
    }
}
