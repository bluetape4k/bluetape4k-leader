package io.bluetape4k.leader.metrics

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGt
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * Identified recorder context가 기존 overload에서 누락될 때 warning과 누락 횟수를 기록합니다.
 *
 * Warning 중복 제거는 최근 사용 순서의 LRU 캐시이며, [cacheSize]개 recorder class까지만 유지합니다.
 * 캐시에서 제거된 class가 다시 context를 누락하면 warning을 다시 기록합니다.
 *
 * @property cacheSize warning 중복 제거에 보관할 최대 recorder class 수입니다. 양수여야 합니다.
 */
class LeaderRecorderContextDropLog(val cacheSize: Int = 256) {

    init {
        cacheSize.requireGt(0, "cacheSize")
    }

    private val lock = ReentrantLock()
    private val warnedClasses: LinkedHashMap<KClass<*>, Boolean> =
        object: LinkedHashMap<KClass<*>, Boolean>(cacheSize, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: Map.Entry<KClass<*>, Boolean>): Boolean =
                size > cacheSize
        }
    private val droppedCounter = AtomicLong(0L)

    /**
     * Identified context에서 누락된 recorder 호출의 누적 횟수를 반환합니다.
     *
     * @return 이 인스턴스가 생성된 뒤 누락된 Identified context 호출의 누적 횟수입니다.
     */
    fun droppedCount(): Long = droppedCounter.get()

    /**
     * Identified context가 recorder class의 legacy overload에서 누락되면 횟수를 올리고 warning을 기록합니다.
     *
     * 같은 class의 warning은 해당 class가 [cacheSize] LRU 항목에 남아 있는 동안 한 번만 기록합니다.
     * Unknown context는 횟수와 warning에 영향을 주지 않습니다.
     *
     * @param recorderClass context overload를 제공하지 않은 recorder class입니다.
     * @param context recorder에 전달된 leader context입니다.
     */
    fun warnOnDrop(recorderClass: KClass<*>, context: LeaderAopMetricsContext) {
        if (context !is LeaderAopMetricsContext.Identified) return
        droppedCounter.incrementAndGet()

        val firstWarning = lock.withLock {
            warnedClasses.putIfAbsent(recorderClass, true) == null
        }
        if (firstWarning) {
            log.warn {
                "[OMC-RECORDER-CTX-DROP] ${recorderClass.qualifiedName} does not override context overloads. " +
                        "leaderId='${context.leaderId}' dropped. Override the context overloads to capture leader ID metrics."
            }
        }
    }

    companion object: KLogging() {
        private const val LOAD_FACTOR: Float = 0.75f

        @Volatile
        private var globalInstance: LeaderRecorderContextDropLog = LeaderRecorderContextDropLog()

        /**
         * `global` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun global(): LeaderRecorderContextDropLog = globalInstance

        /**
         * `setGlobal` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param instance `instance` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun setGlobal(instance: LeaderRecorderContextDropLog) {
            val prev = globalInstance
            globalInstance = instance
            log.info { "[LeaderRecorderContextDropLog] global instance swapped. prev.droppedCount=${prev.droppedCount()}" }
        }
    }
}
