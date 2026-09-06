package io.bluetape4k.leader.contract

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElector
import io.bluetape4k.leader.StrategicLeaderGroupElector
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElector
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class CustomStrategicBackendConformanceTest : AbstractStrategicBackendConformanceTest() {

    override fun createProvider(): StrategicBackendConformanceProvider = CustomStrategicBackendProvider()
}

private class CustomStrategicBackendProvider : StrategicBackendConformanceProvider {
    private val stores = StrategicBackendKind.entries.associateWith { CustomCandidateStore() }

    override fun blocking(kind: StrategicBackendKind, nodeId: String): BlockingStrategicBackend =
        when (kind) {
            StrategicBackendKind.SINGLE -> CustomBlockingSingleBackend(nodeId, store(kind))
            StrategicBackendKind.GROUP -> CustomBlockingGroupBackend(nodeId, store(kind))
        }

    override fun suspending(kind: StrategicBackendKind, nodeId: String): SuspendStrategicBackend =
        when (kind) {
            StrategicBackendKind.SINGLE -> CustomSuspendSingleBackend(nodeId, store(kind))
            StrategicBackendKind.GROUP -> CustomSuspendGroupBackend(nodeId, store(kind))
        }

    override fun awaitCandidateExpiration(
        kind: StrategicBackendKind,
        lockName: String,
        nodeId: String,
        timeout: Duration,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (store(kind).list(lockName).any { it.nodeId == nodeId }) {
            if (System.nanoTime() - deadline >= 0L) return false
            Thread.sleep(5L)
        }
        return true
    }

    override fun close() {
        stores.values.forEach(CustomCandidateStore::clear)
    }

    private fun store(kind: StrategicBackendKind): CustomCandidateStore = checkNotNull(stores[kind])
}

private class CustomBlockingSingleBackend(
    override val nodeId: String,
    private val store: CustomCandidateStore,
) : StrategicLeaderElector, BlockingStrategicBackend {

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.register(lockName, info, ttl)

    override fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.refresh(lockName, info, ttl)

    override fun unregisterCandidate(lockName: String, nodeId: String) = store.unregister(lockName, nodeId)

    override fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        store.updateResult(lockName, nodeId, result)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runIfLeader(lockName, FifoElectionStrategy, LeaderElectionOptions.Default, action)

    override fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: () -> T,
    ): T? {
        if (strategy.elect(store.list(lockName)).winner?.nodeId != nodeId) return null
        return try {
            action().also { store.updateResult(lockName, nodeId, CandidateResult.SUCCESS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            store.updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

private class CustomBlockingGroupBackend(
    override val nodeId: String,
    private val store: CustomCandidateStore,
) : StrategicLeaderGroupElector, BlockingStrategicBackend {

    override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.register(lockName, info, ttl)

    override fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.refresh(lockName, info, ttl)

    override fun unregisterCandidate(lockName: String, nodeId: String) = store.unregister(lockName, nodeId)

    override fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

    override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        store.updateResult(lockName, nodeId, result)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runIfLeader(lockName, FifoGroupElectionStrategy, 1, action)

    override fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: () -> T,
    ): T? {
        if (strategy.elect(store.list(lockName), maxLeaders).winners.none { it.nodeId == nodeId }) return null
        return try {
            action().also { store.updateResult(lockName, nodeId, CandidateResult.SUCCESS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Throwable) {
            store.updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

private class CustomSuspendSingleBackend(
    override val nodeId: String,
    private val store: CustomCandidateStore,
) : StrategicSuspendLeaderElector, SuspendStrategicBackend {

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.register(lockName, info, ttl)

    override suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.refresh(lockName, info, ttl)

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) = store.unregister(lockName, nodeId)

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        store.updateResult(lockName, nodeId, result)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runIfLeader(lockName, FifoElectionStrategy, LeaderElectionOptions.Default, action)

    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions,
        action: suspend () -> T,
    ): T? {
        if (strategy.elect(store.list(lockName)).winner?.nodeId != nodeId) return null
        return try {
            action().also { store.updateResult(lockName, nodeId, CandidateResult.SUCCESS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

private class CustomSuspendGroupBackend(
    override val nodeId: String,
    private val store: CustomCandidateStore,
) : StrategicSuspendLeaderGroupElector, SuspendStrategicBackend {

    override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.register(lockName, info, ttl)

    override suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration) =
        store.refresh(lockName, info, ttl)

    override suspend fun unregisterCandidate(lockName: String, nodeId: String) = store.unregister(lockName, nodeId)

    override suspend fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

    override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) =
        store.updateResult(lockName, nodeId, result)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runIfLeader(lockName, FifoGroupElectionStrategy, 1, action)

    override suspend fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int,
        action: suspend () -> T,
    ): T? {
        if (strategy.elect(store.list(lockName), maxLeaders).winners.none { it.nodeId == nodeId }) return null
        return try {
            action().also { store.updateResult(lockName, nodeId, CandidateResult.SUCCESS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.updateResult(lockName, nodeId, CandidateResult.FAILURE)
            throw e
        }
    }
}

private class CustomCandidateStore {
    private val candidates = ConcurrentHashMap<CandidateKey, CandidateEntry>()

    fun register(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidates[CandidateKey(lockName, info.nodeId)] = CandidateEntry(info, expiresAt(ttl))
    }

    fun refresh(lockName: String, info: CandidateInfo, ttl: Duration) {
        candidates.computeIfPresent(CandidateKey(lockName, info.nodeId)) { _, current ->
            if (current.isExpired()) null
            else CandidateEntry(current.info.copy(metadata = info.metadata), expiresAt(ttl))
        }
    }

    fun unregister(lockName: String, nodeId: String) {
        candidates.remove(CandidateKey(lockName, nodeId))
    }

    fun list(lockName: String): List<CandidateInfo> = candidates
        .filterKeys { it.lockName == lockName }
        .values
        .filterNot(CandidateEntry::isExpired)
        .map(CandidateEntry::info)

    fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
        candidates.computeIfPresent(CandidateKey(lockName, nodeId)) { _, current ->
            if (current.isExpired()) null
            else current.copy(info = current.info.withResult(result))
        }
    }

    fun clear() {
        candidates.clear()
    }

    private fun expiresAt(ttl: Duration): Long? =
        if (ttl > Duration.ZERO) System.nanoTime() + ttl.inWholeNanoseconds else null
}

private data class CandidateKey(
    val lockName: String,
    val nodeId: String,
)

private data class CandidateEntry(
    val info: CandidateInfo,
    val expiresAtNanos: Long?,
) {
    fun isExpired(): Boolean = expiresAtNanos?.let { System.nanoTime() - it >= 0L } ?: false
}
