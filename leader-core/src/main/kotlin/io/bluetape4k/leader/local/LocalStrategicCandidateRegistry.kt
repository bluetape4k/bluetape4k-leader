package io.bluetape4k.leader.local

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Local strategic elector가 공유하는 thread-safe 후보 registry입니다. */
internal class LocalStrategicCandidateRegistry(
    private val monotonicNowNanos: () -> Long = { System.nanoTime() },
) {

    private val candidates = ConcurrentHashMap<String, RegisteredCandidate>()

    fun register(info: CandidateInfo, ttl: Duration) {
        candidates[info.nodeId] = RegisteredCandidate(
            info = info,
            registeredAtNanos = monotonicNowNanos(),
            ttlNanos = ttlNanos(ttl),
        )
    }

    fun refresh(info: CandidateInfo, ttl: Duration) {
        val nowNanos = monotonicNowNanos()
        candidates.computeIfPresent(info.nodeId) { _, current ->
            if (current.isExpired(nowNanos)) {
                null
            } else {
                current.copy(
                    info = current.info.copy(metadata = info.metadata),
                    registeredAtNanos = nowNanos,
                    ttlNanos = ttlNanos(ttl),
                )
            }
        }
    }

    fun unregister(nodeId: String) {
        candidates.remove(nodeId)
    }

    fun list(): List<CandidateInfo> {
        val nowNanos = monotonicNowNanos()
        candidates.keys.forEach { nodeId ->
            candidates.computeIfPresent(nodeId) { _, current ->
                if (current.isExpired(nowNanos)) null else current
            }
        }
        return candidates.values.map(RegisteredCandidate::info)
    }

    fun updateResult(nodeId: String, result: CandidateResult) {
        val nowNanos = monotonicNowNanos()
        candidates.computeIfPresent(nodeId) { _, current ->
            if (current.isExpired(nowNanos)) null else current.copy(info = current.info.withResult(result))
        }
    }

    private fun ttlNanos(ttl: Duration): Long? {
        if (ttl <= Duration.ZERO || ttl == Duration.INFINITE) return null
        return ttl.inWholeNanoseconds.coerceAtLeast(1L)
    }

    private data class RegisteredCandidate(
        val info: CandidateInfo,
        val registeredAtNanos: Long,
        val ttlNanos: Long?,
    ): Serializable {
        fun isExpired(nowNanos: Long): Boolean =
            ttlNanos?.let { nowNanos - registeredAtNanos >= it } == true

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }
}
