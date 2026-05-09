package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * 로컬 elector 구현체에서 사용하는 in-memory 리더 상태 registry입니다.
 *
 * `ReentrantLock`과 semaphore 자체는 현재 owner metadata를 노출하지 않으므로, acquire/release 경로에서
 * 별도의 lease 스냅샷을 기록합니다.
 */
internal class LocalLeaderStateRegistry {

    private val lock = ReentrantLock()
    private val singleLeases = HashMap<String, LeaseRef>()
    private val groupLeases = HashMap<String, MutableMap<Int, LeaderLease>>()

    fun acquireSingle(lockName: String, nodeId: String, leaseTime: Duration): LeaderLease =
        lock.withLock {
            val lease = newLease(nodeId, leaseTime)
            val current = singleLeases[lockName]
            if (current == null) {
                singleLeases[lockName] = LeaseRef(lease)
            } else {
                current.holdCount += 1
            }
            singleLeases.getValue(lockName).lease
        }

    fun releaseSingle(lockName: String) {
        lock.withLock {
            val current = singleLeases[lockName] ?: return
            current.holdCount -= 1
            if (current.holdCount <= 0) {
                singleLeases.remove(lockName)
            }
        }
    }

    fun singleState(lockName: String): LeaderState {
        lockName.requireNotBlank("lockName")
        return lock.withLock {
            singleLeases[lockName]?.lease
                ?.let { LeaderState.occupied(lockName, it) }
                ?: LeaderState.empty(lockName)
        }
    }

    fun acquireGroup(lockName: String, nodeId: String, leaseTime: Duration, maxLeaders: Int): LeaderLease =
        lock.withLock {
            val leases = groupLeases.getOrPut(lockName) { HashMap() }
            val slot = (0 until maxLeaders).firstOrNull { it !in leases }
                ?: leases.size
            val lease = newLease(nodeId, leaseTime, slot)
            leases[slot] = lease
            lease
        }

    fun releaseGroup(lockName: String, lease: LeaderLease) {
        lock.withLock {
            val slot = lease.slot ?: return
            val leases = groupLeases[lockName] ?: return
            leases.remove(slot)
            if (leases.isEmpty()) {
                groupLeases.remove(lockName)
            }
        }
    }

    fun groupState(lockName: String, maxLeaders: Int, activeCount: Int): LeaderGroupState {
        lockName.requireNotBlank("lockName")
        return lock.withLock {
            val leaders = groupLeases[lockName]
                ?.values
                ?.sortedBy { it.slot }
                ?: emptyList()
            LeaderGroupState(lockName, maxLeaders, activeCount, leaders)
        }
    }

    private fun newLease(nodeId: String, leaseTime: Duration, slot: Int? = null): LeaderLease {
        val electedAt = Instant.now()
        return LeaderLease(
            leaderId = nodeId,
            electedAt = electedAt,
            leaseUntil = electedAt.plusMillis(leaseTime.inWholeMilliseconds),
            slot = slot,
        )
    }

    private data class LeaseRef(
        val lease: LeaderLease,
        var holdCount: Int = 1,
    )
}
