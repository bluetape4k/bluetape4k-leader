package io.bluetape4k.leader.metrics

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * `LeaderRecorderContextDropLog`는 leader election audit/history 저장 계약을 표현합니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property cacheSize `cacheSize` 호출 또는 상태 계산에 필요한 값입니다.
 */
class LeaderRecorderContextDropLog(val cacheSize: Int = 256) {

    private val warnedClasses = ConcurrentHashMap.newKeySet<KClass<*>>()
    private val droppedCounter = AtomicLong(0L)

    /**
     * `droppedCount` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun droppedCount(): Long = droppedCounter.get()

    /**
     * `warnOnDrop` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param recorderClass `recorderClass` 호출 또는 상태 계산에 필요한 값입니다.
     * @param context `context` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun warnOnDrop(recorderClass: KClass<*>, context: LeaderAopMetricsContext) {
        if (context !is LeaderAopMetricsContext.Identified) return
        droppedCounter.incrementAndGet()
        if (warnedClasses.add(recorderClass)) {
            log.warn {
                "[OMC-RECORDER-CTX-DROP] ${recorderClass.qualifiedName} does not override context overloads. " +
                    "leaderId='${context.leaderId}' dropped. Override the context overloads to capture leader ID metrics."
            }
        }
    }

    companion object : KLogging() {
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
