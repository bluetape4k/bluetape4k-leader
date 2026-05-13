package io.bluetape4k.leader.metrics

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * Throttled drop logger for [LeaderAopMetricsRecorder] implementations that do not override
 * the context-bearing overloads.
 *
 * ## Contract
 * - [warnOnDrop] is a no-op when [context] is [LeaderAopMetricsContext.Unknown].
 * - For [LeaderAopMetricsContext.Identified]: increments [droppedCounter] on every call,
 *   but only logs a WARN once per recorder class (first-add semantics).
 * - Thread-safe: [ConcurrentHashMap.newKeySet] + [AtomicLong].
 *
 * ## Global Holder
 * Use [global] / [setGlobal] to manage the singleton instance.
 * In tests, call `setGlobal(LeaderRecorderContextDropLog())` in `@BeforeEach` to reset state.
 *
 * ## Usage
 * ```kotlin
 * LeaderRecorderContextDropLog.global().warnOnDrop(MyRecorder::class, context)
 * val dropped = LeaderRecorderContextDropLog.global().droppedCount()
 * ```
 */
class LeaderRecorderContextDropLog(val cacheSize: Int = 256) {

    private val warnedClasses = ConcurrentHashMap.newKeySet<KClass<*>>()
    private val droppedCounter = AtomicLong(0L)

    /** Returns the total number of context drops recorded (including repeated drops from same class). */
    fun droppedCount(): Long = droppedCounter.get()

    /**
     * Records a context drop for [recorderClass].
     *
     * - [LeaderAopMetricsContext.Unknown]: early return, no counter increment, no warn.
     * - [LeaderAopMetricsContext.Identified]: always increments [droppedCounter]; WARN only on first call per class.
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

        /** Returns the current global [LeaderRecorderContextDropLog] instance. */
        fun global(): LeaderRecorderContextDropLog = globalInstance

        /**
         * Replaces the global instance. Logs the previous instance's drop count.
         *
         * Warning: In tests, `@DirtiesContext` alone does NOT reset this holder. Call [setGlobal] explicitly in `@BeforeEach`.
         */
        fun setGlobal(instance: LeaderRecorderContextDropLog) {
            val prev = globalInstance
            globalInstance = instance
            log.info { "[LeaderRecorderContextDropLog] global instance swapped. prev.droppedCount=${prev.droppedCount()}" }
        }
    }
}
