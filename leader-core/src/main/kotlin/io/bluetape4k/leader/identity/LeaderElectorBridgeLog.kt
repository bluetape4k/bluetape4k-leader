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
 * slot overload를 구현하지 않은 elector의 audit ID 손실을 감지하고 경고합니다.
 *
 * @property cacheSize 최근 경고 key를 보관하는 LRU cache의 최대 크기입니다. 양수여야 하며 기본값은 `128`입니다.
 */
class LeaderElectorBridgeLog(val cacheSize: Int = DEFAULT_CACHE_SIZE) {

    init {
        cacheSize.requireGt(0, "cacheSize")
    }

    private val lock: ReentrantLock = ReentrantLock()

    private val warnedPairs: LinkedHashMap<String, Boolean> =
        object: LinkedHashMap<String, Boolean>(cacheSize, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                size > cacheSize
        }

    private val warnedResultPairs: LinkedHashMap<String, Boolean> =
        object: LinkedHashMap<String, Boolean>(cacheSize, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean =
                size > cacheSize
        }

    private val droppedCounter: AtomicLong = AtomicLong(0L)
    private val droppedResultCounter: AtomicLong = AtomicLong(0L)

    /**
     * `LeaderSlot` 경로에서 bridge default가 사용된 총 호출 수입니다. 중복 경고 여부와 무관하게 매 호출 증가합니다.
     */
    fun droppedAuditCount(): Long = droppedCounter.get()

    /**
     * `LeaderRunResult`의 slot overload에서 bridge default가 사용된 총 호출 수입니다.
     */
    fun droppedResultBridgeCount(): Long = droppedResultCounter.get()

    /**
     * implementation과 leader ID 조합마다 bridge 경고를 한 번 기록합니다. key가 LRU에서 제거되면 다시 경고할 수 있습니다.
     * @param implClass `LeaderSlot` overload가 기본 구현을 사용하는 elector 구현 클래스입니다.
     * @param slot lock 이름과 audit에 전달할 leader ID를 가진 선택 slot입니다.
     */
    fun warnOnBridgeUse(implClass: KClass<*>, slot: LeaderSlot) {
        droppedCounter.incrementAndGet()
        val key = "${implClass.qualifiedName}|slot|${slot.leaderId}"
        val isNew = lock.withLock { warnedPairs.putIfAbsent(key, true) == null }

        if (isNew) {
            log.warn {
                "[BRIDGE-SLOT-DROP] ${implClass.qualifiedName} uses bridge default for slot " +
                        "lockName='${slot.lockName.sanitizeForLog()}'. Override runIfLeader(LeaderSlot, action) " +
                        "to stamp " +
                        "slot.leaderId into LeaderLease.auditLeaderId and avoid audit identity loss."
            }
        }
    }

    /**
     * result overload를 재정의하지 않은 elector에 경고합니다. 두 slot 경로별 LRU에서 key를 제거하면 다시 경고할 수 있습니다.
     * @param implClass `LeaderRunResult` slot overload가 기본 구현을 사용하는 elector 구현 클래스입니다.
     * @param slot lock 이름과 audit에 전달할 leader ID를 가진 선택 slot입니다.
     */
    fun warnOnResultBridgeUse(implClass: KClass<*>, slot: LeaderSlot) {
        droppedResultCounter.incrementAndGet()
        val key = "${implClass.qualifiedName}|slot|${slot.leaderId}"
        val isNew = lock.withLock { warnedResultPairs.putIfAbsent(key, true) == null }

        if (isNew) {
            log.warn {
                "[BRIDGE-RESULT-DROP] ${implClass.qualifiedName} uses result bridge default for slot " +
                        "lockName='${slot.lockName.sanitizeForLog()}'. Backend MUST override BOTH slot and result variants " +
                        "(runIfLeader + runIfLeaderResult) to capture leader ID into LeaderRunResult.Elected.leaderId."
            }
        }
    }

    companion object: KLogging() {
        private const val DEFAULT_CACHE_SIZE: Int = 128
        private const val LOAD_FACTOR: Float = 0.75f

        /**
         * log entry 안의 control character를 `?`로 바꿉니다.
         */
        private fun String.sanitizeForLog(): String =
            replace(Regex("\\p{Cntrl}"), "?")

        @Volatile
        private var globalInstance: LeaderElectorBridgeLog = LeaderElectorBridgeLog()

        /**
         * process-wide bridge logger를 반환합니다.
         */
        fun global(): LeaderElectorBridgeLog = globalInstance

        /**
         * process-wide bridge logger를 교체합니다.
         * @param instance 이후 bridge 경고와 counter 조회에 사용할 logger입니다.
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
