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
 * `LeaderElectorBridgeLog` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property cacheSize `cacheSize` 호출 또는 상태 계산에 필요한 값입니다.
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

    /**
     * `droppedAuditCount` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun droppedAuditCount(): Long = droppedCounter.get()

    /**
     * `droppedResultBridgeCount` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun droppedResultBridgeCount(): Long = droppedResultCounter.get()

    /**
     * `warnOnBridgeUse` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param implClass `implClass` 호출 또는 상태 계산에 필요한 값입니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun warnOnBridgeUse(implClass: KClass<*>, slot: LeaderSlot) {
        droppedCounter.incrementAndGet()
        val key = "${implClass.qualifiedName}|slot|${slot.leaderId}"
        val isNew = lock.withLock { warnedPairs.putIfAbsent(key, true) == null }
        if (isNew) {
            log.warn {
                "[OMC-BRIDGE-SLOT-DROP] ${implClass.qualifiedName} uses bridge default for slot " +
                    "lockName='${slot.lockName.sanitizeForLog()}'. Override runIfLeader(LeaderSlot, action) to stamp " +
                    "slot.leaderId into LeaderLease.auditLeaderId and avoid audit identity loss."
            }
        }
    }

    /**
     * `warnOnResultBridgeUse` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param implClass `implClass` 호출 또는 상태 계산에 필요한 값입니다.
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun warnOnResultBridgeUse(implClass: KClass<*>, slot: LeaderSlot) {
        droppedResultCounter.incrementAndGet()
        val key = "${implClass.qualifiedName}|slot|${slot.leaderId}"
        val isNew = lock.withLock { warnedResultPairs.putIfAbsent(key, true) == null }
        if (isNew) {
            log.warn {
                "[OMC-BRIDGE-RESULT-DROP] ${implClass.qualifiedName} uses result bridge default for slot " +
                    "lockName='${slot.lockName.sanitizeForLog()}'. Backend MUST override BOTH slot and result variants " +
                    "(runIfLeader + runIfLeaderResult) to capture leader ID into LeaderRunResult.Elected.leaderId."
            }
        }
    }

    companion object : KLogging() {
        private const val DEFAULT_CACHE_SIZE: Int = 128
        private const val LOAD_FACTOR: Float = 0.75f

        /**
         * `String` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        private fun String.sanitizeForLog(): String =
            replace(Regex("\\p{Cntrl}"), "?")

        @Volatile
        private var globalInstance: LeaderElectorBridgeLog = LeaderElectorBridgeLog()

        /**
         * `global` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun global(): LeaderElectorBridgeLog = globalInstance

        /**
         * `setGlobal` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param instance `instance` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
