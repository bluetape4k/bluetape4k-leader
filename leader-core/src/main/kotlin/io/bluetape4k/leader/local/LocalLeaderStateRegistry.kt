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
 * In-memory leader state registry used by local elector implementations.
 *
 * Because `ReentrantLock` and semaphores do not expose current owner metadata,
 * separate lease snapshots are recorded on the acquire/release path.
 */
internal class LocalLeaderStateRegistry {

    private val lock = ReentrantLock()
    private val singleLeases = HashMap<String, LeaseRef>()
    private val groupLeases = HashMap<String, MutableMap<Int, LeaderLease>>()

    fun acquireSingle(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = null,
        leaseTime: Duration,
    ): LeaderLease =
        lock.withLock {
            val lease = newLease(auditLeaderId, nodeId, leaseTime)
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

    fun extendSingle(lockName: String, leaseTime: Duration): Boolean =
        lock.withLock {
            val current = singleLeases[lockName] ?: return false
            current.lease = current.lease.copy(
                leaseUntil = Instant.now().plusMillis(leaseTime.inWholeMilliseconds),
            )
            true
        }

    fun singleState(lockName: String): LeaderState {
        lockName.requireNotBlank("lockName")
        return lock.withLock {
            singleLeases[lockName]?.lease
                ?.let { LeaderState.occupied(lockName, it) }
                ?: LeaderState.empty(lockName)
        }
    }

    fun acquireGroup(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = null,
        leaseTime: Duration,
        maxLeaders: Int,
    ): LeaderLease =
        lock.withLock {
            val leases = groupLeases.getOrPut(lockName) { HashMap() }
            val slot = (0 until maxLeaders).firstOrNull { it !in leases }
                ?: leases.size
            val lease = newLease(auditLeaderId, nodeId, leaseTime, slot)
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

    fun extendGroup(lockName: String, slot: Int, leaseTime: Duration): Boolean =
        lock.withLock {
            val leases = groupLeases[lockName] ?: return false
            val current = leases[slot] ?: return false
            leases[slot] = current.copy(
                leaseUntil = Instant.now().plusMillis(leaseTime.inWholeMilliseconds),
            )
            true
        }

    fun isSlotHeld(lockName: String, slot: Int): Boolean =
        lock.withLock {
            groupLeases[lockName]?.containsKey(slot) == true
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

    private fun newLease(
        auditLeaderId: String,
        nodeId: String? = null,
        leaseTime: Duration,
        slot: Int? = null,
    ): LeaderLease {
        val electedAt = Instant.now()
        return LeaderLease(
            auditLeaderId = auditLeaderId,
            nodeId = nodeId,
            electedAt = electedAt,
            leaseUntil = electedAt.plusMillis(leaseTime.inWholeMilliseconds),
            slot = slot,
        )
    }

    private data class LeaseRef(
        var lease: LeaderLease,
        var holdCount: Int = 1,
    )
}
