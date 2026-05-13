package io.bluetape4k.leader.identity

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGt
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * Throttled WARN logger for backend elector implementations that fall through to the bridge default
 * instead of overriding the [LeaderSlot] overload.
 *
 * ## Contract
 * - Warns at most once per `(implClass, leaderId)` pair, bounded by [cacheSize] (LRU eviction).
 * - `warnedPairs` and `warnedResultPairs` are kept separate to prevent mutual LRU eviction
 *   between slot bridge and result bridge use sites.
 * - All [LinkedHashMap] reads and mutations are protected by an internal [ReentrantLock]
 *   (Virtual-Thread-safe — no `synchronized`).
 * - Drop counters ([droppedAuditCount], [droppedResultBridgeCount]) are incremented outside the lock
 *   using [AtomicLong] (no contention with WARN throttling).
 *
 * ## AUTO Source LRU Limitation
 * AUTO source ([LeaderIdSource.AUTO]) generates a unique-per-call [LeaderSlot.leaderId].
 * This causes LRU churn that defeats throttling, producing an unthrottled WARN flood
 * when backends rely on bridge defaults with AUTO source. See T81 follow-up for dedicated
 * AUTO source handling.
 *
 * ## Global Holder
 * Use [global] / [setGlobal] to manage the process-wide singleton.
 *
 * ⚠️ In tests, `@DirtiesContext` alone does NOT reset this holder. Call
 * `setGlobal(LeaderElectorBridgeLog())` explicitly in `@BeforeEach`.
 *
 * ## Usage
 * ```kotlin
 * LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)
 * val dropped = LeaderElectorBridgeLog.global().droppedAuditCount()
 * ```
 */
class LeaderElectorBridgeLog(val cacheSize: Int = DEFAULT_CACHE_SIZE) {

    init {
        cacheSize.requireGt(0, "cacheSize")
    }

    private val lock: ReentrantLock = ReentrantLock()

    private val warnedPairs: LinkedHashMap<String, Boolean> =
        object : LinkedHashMap<String, Boolean>(cacheSize, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                size > cacheSize
        }

    private val warnedResultPairs: LinkedHashMap<String, Boolean> =
        object : LinkedHashMap<String, Boolean>(cacheSize, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                size > cacheSize
        }

    private val droppedCounter: AtomicLong = AtomicLong(0L)
    private val droppedResultCounter: AtomicLong = AtomicLong(0L)

    /** Total number of slot bridge drops (including repeated drops after LRU eviction). */
    fun droppedAuditCount(): Long = droppedCounter.get()

    /** Total number of result bridge drops (including repeated drops after LRU eviction). */
    fun droppedResultBridgeCount(): Long = droppedResultCounter.get()

    /**
     * Records and throttle-warns that [implClass] used the bridge default for [slot].
     *
     * Warns at most once per `(implClass, slot.leaderId)` pair (LRU-bounded by [cacheSize]).
     * Increments [droppedAuditCount] on every call.
     *
     * @param implClass the backend elector implementation class that did not override the slot overload.
     * @param slot the [LeaderSlot] passed by the caller.
     */
    fun warnOnBridgeUse(implClass: KClass<*>, slot: LeaderSlot) {
        droppedCounter.incrementAndGet()
        val key = "${implClass.qualifiedName}|slot|${slot.leaderId}"
        val isNew = lock.withLock { warnedPairs.putIfAbsent(key, true) == null }
        if (isNew) {
            log.warn {
                "[OMC-BRIDGE-SLOT-DROP] ${implClass.qualifiedName} uses bridge default for slot " +
                    "lockName='${slot.lockName}'. Override runIfLeader(LeaderSlot, action) to stamp " +
                    "slot.leaderId into LeaderLease.auditLeaderId and avoid audit identity loss."
            }
        }
    }

    /**
     * Records and throttle-warns that [implClass] used the result bridge default for [slot].
     *
     * Warns at most once per `(implClass, slot.leaderId)` pair (LRU-bounded, separate from slot map).
     * Increments [droppedResultBridgeCount] on every call.
     *
     * The backend MUST override BOTH slot and result variants to silence this warning — overriding
     * only the slot variant still drops [LeaderSlot.leaderId] from the [io.bluetape4k.leader.LeaderRunResult.Elected]
     * payload.
     *
     * @param implClass the backend elector implementation class that did not override the result overload.
     * @param slot the [LeaderSlot] passed by the caller.
     */
    fun warnOnResultBridgeUse(implClass: KClass<*>, slot: LeaderSlot) {
        droppedResultCounter.incrementAndGet()
        val key = "${implClass.qualifiedName}|slot|${slot.leaderId}"
        val isNew = lock.withLock { warnedResultPairs.putIfAbsent(key, true) == null }
        if (isNew) {
            log.warn {
                "[OMC-BRIDGE-RESULT-DROP] ${implClass.qualifiedName} uses result bridge default for slot " +
                    "lockName='${slot.lockName}'. Backend MUST override BOTH slot and result variants " +
                    "(runIfLeader + runIfLeaderResult) to capture leader ID into LeaderRunResult.Elected.leaderId."
            }
        }
    }

    companion object : KLogging() {
        private const val DEFAULT_CACHE_SIZE: Int = 128
        private const val LOAD_FACTOR: Float = 0.75f

        @Volatile
        private var globalInstance: LeaderElectorBridgeLog = LeaderElectorBridgeLog()

        /** Returns the current global [LeaderElectorBridgeLog] instance. */
        fun global(): LeaderElectorBridgeLog = globalInstance

        /**
         * Replaces the global instance and logs the previous instance's drop counts.
         *
         * ⚠️ In tests, `@DirtiesContext` alone does NOT reset this holder.
         * Call `setGlobal(LeaderElectorBridgeLog())` explicitly in `@BeforeEach`.
         *
         * @param instance the new [LeaderElectorBridgeLog] instance to install as the process-wide global.
         */
        fun setGlobal(instance: LeaderElectorBridgeLog) {
            val prev = globalInstance
            globalInstance = instance
            log.info {
                "[LeaderElectorBridgeLog] global instance swapped. " +
                    "prev.droppedAuditCount=${prev.droppedAuditCount()}, " +
                    "prev.droppedResultBridgeCount=${prev.droppedResultBridgeCount()}"
            }
        }
    }
}
