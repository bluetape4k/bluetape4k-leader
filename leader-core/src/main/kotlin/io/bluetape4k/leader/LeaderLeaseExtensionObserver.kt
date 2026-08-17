package io.bluetape4k.leader

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

@JvmSynthetic
@Volatile
internal var leaderLeaseExtensionDispatcher: Executor = VirtualThreadExecutor

/** lease extension을 호출한 경계를 구분합니다. */
enum class LeaderLeaseExtensionSource {
    USER,
    WATCHDOG,
}

/** lease extension이 실행된 모델을 구분합니다. */
enum class LeaderLeaseExtensionExecution {
    BLOCKING,
    SUSPEND,
}

/** lease extension 시점의 ownership context입니다.
 *
 * 이 값은 관찰 callback에만 전달되며, `lockName`과 `auditLeaderId`를
 * `toString()`이나 직렬화 결과에 노출하지 않습니다.
 */
class LeaderLeaseExtensionContext(
    val lockName: String,
    val auditLeaderId: String?,
) {

    init {
        lockName.requireNotBlank("lockName")
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is LeaderLeaseExtensionContext &&
            lockName == other.lockName &&
            auditLeaderId == other.auditLeaderId)

    override fun hashCode(): Int = 31 * lockName.hashCode() + (auditLeaderId?.hashCode() ?: 0)

    override fun toString(): String = "LeaderLeaseExtensionContext(<redacted>)"
}

/** 하나의 terminal lease extension 시도를 나타내는 immutable event입니다. */
class LeaderLeaseExtensionEvent(
    val source: LeaderLeaseExtensionSource,
    val execution: LeaderLeaseExtensionExecution,
    val outcome: ExtendOutcome,
    val elapsedNanos: Long,
    val context: LeaderLeaseExtensionContext?,
) {

    init {
        elapsedNanos.requireGe(0L, "elapsedNanos")
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is LeaderLeaseExtensionEvent &&
            source == other.source &&
            execution == other.execution &&
            outcome == other.outcome &&
            elapsedNanos == other.elapsedNanos &&
            context == other.context)

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + execution.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + elapsedNanos.hashCode()
        result = 31 * result + (context?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "LeaderLeaseExtensionEvent(source=$source, execution=$execution, outcome=${outcome::class.simpleName})"
}

/** lease extension terminal event를 받는 public SAM callback입니다. */
fun interface LeaderLeaseExtensionObserver {
    /** event를 소비합니다. callback 예외는 extension 결과에 영향을 주지 않습니다. */
    fun onExtension(event: LeaderLeaseExtensionEvent)
}

/** process-local lease extension observer registry입니다. */
object LeaderLeaseExtensionObservers {

    private const val MAX_IN_FLIGHT = 1024
    private const val MAX_IN_FLIGHT_PER_OBSERVER = 256
    private const val WARNING_INTERVAL_NANOS = 1_000_000_000L

    private val registrations = CopyOnWriteArrayList<Registration>()
    private val globalInFlight = Semaphore(MAX_IN_FLIGHT)
    private val dropped = AtomicLong(0L)

    /** observer를 등록하고 해당 registration만 닫는 handle을 반환합니다. */
    @JvmStatic
    fun addObserver(observer: LeaderLeaseExtensionObserver): AutoCloseable {
        val registration = Registration(observer)
        registrations.add(registration)
        return AutoCloseable {
            if (registration.closed.compareAndSet(false, true)) {
                registrations.remove(registration)
            }
        }
    }

    /** 동일 object identity의 registration을 모두 제거합니다. */
    @JvmStatic
    fun removeObserver(observer: LeaderLeaseExtensionObserver): Boolean {
        val removed = AtomicBoolean(false)
        registrations.removeIf { registration ->
            if (registration.observer === observer) {
                registration.closed.set(true)
                removed.set(true)
                true
            } else {
                false
            }
        }
        return removed.get()
    }

    /** admission 실패로 누락된 observer delivery의 누적 수를 반환합니다. */
    @JvmStatic
    fun droppedCount(): Long = dropped.get()

    /** event/context allocation 전에 observer 존재 여부를 확인하는 internal bridge입니다. */
    @JvmSynthetic
    internal fun hasObservers(): Boolean = registrations.isNotEmpty()

    /** caller가 만든 terminal event를 bounded virtual-thread dispatcher에 제출합니다. */
    @JvmSynthetic
    @Suppress("TooGenericExceptionCaught")
    internal fun publish(event: LeaderLeaseExtensionEvent) {
        val snapshot = registrations.toTypedArray()
        if (snapshot.isEmpty()) return

        var index = 0
        while (index < snapshot.size) {
            val registration = snapshot[index]
            if (!globalInFlight.tryAcquire()) {
                while (index < snapshot.size) {
                    recordDrop(snapshot[index])
                    index++
                }
                return
            }

            if (!registration.inFlight.tryAcquire()) {
                globalInFlight.release()
                recordDrop(registration)
                index++
                continue
            }

            try {
                leaderLeaseExtensionDispatcher.execute {
                    try {
                        registration.observer.onExtension(event)
                    } catch (_: Exception) {
                        recordCallbackFailure(registration)
                    } finally {
                        registration.inFlight.release()
                        globalInFlight.release()
                    }
                }
            } catch (submissionFailure: Throwable) {
                registration.inFlight.release()
                globalInFlight.release()
                recordDrop(registration)
                if (submissionFailure is Error) {
                    throw submissionFailure
                }
            }
            index++
        }
    }

    private fun recordDrop(registration: Registration) {
        dropped.incrementAndGet()
        val now = System.nanoTime()
        val previous = registration.lastWarningNanos.get()
        if (previous != 0L && now - previous < WARNING_INTERVAL_NANOS) return
        if (!registration.lastWarningNanos.compareAndSet(previous, now)) return

        try {
            leaderLeaseExtensionDispatcher.execute {
                log.warn {
                    "LeaderLeaseExtensionObserver delivery dropped due to bounded admission. " +
                        "observer=${registration.safeName}"
                }
            }
        } catch (_: Throwable) {
            // Admission warnings are best effort and must never delay extension.
        }
    }

    private fun recordCallbackFailure(registration: Registration) {
        val now = System.nanoTime()
        val previous = registration.lastCallbackWarningNanos.get()
        if (previous != 0L && now - previous < WARNING_INTERVAL_NANOS) return
        if (!registration.lastCallbackWarningNanos.compareAndSet(previous, now)) return

        log.warn {
            "LeaderLeaseExtensionObserver callback failed and was ignored. " +
                "observer=${registration.safeName}"
        }
    }

    private class Registration(
        val observer: LeaderLeaseExtensionObserver,
    ) {
        val closed = AtomicBoolean(false)
        val inFlight = Semaphore(MAX_IN_FLIGHT_PER_OBSERVER)
        val lastWarningNanos = AtomicLong(0L)
        val lastCallbackWarningNanos = AtomicLong(0L)
        val safeName: String = observer::class.simpleName ?: "anonymous"
    }
}
