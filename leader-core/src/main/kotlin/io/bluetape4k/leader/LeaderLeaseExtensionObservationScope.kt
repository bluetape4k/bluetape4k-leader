package io.bluetape4k.leader

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 호출자가 등록한 lease extension observer에만 event를 연결하는 불투명 scope입니다.
 *
 * 이 handle은 Spring `ObservationRegistry`와 연결되지 않으며 Spring 자동 관측 scope를
 * 가장할 수 없습니다. 사용이 끝나면 반드시 [close]해야 합니다. Kotlin 전용 context
 * bridge는 Java source에서 보이지 않도록 `@JvmSynthetic`으로 제한합니다.
 */
class LeaderLeaseExtensionObservationScope private constructor(
    @get:JvmSynthetic
    internal val observer: LeaderLeaseExtensionObserver,
    private val closeAction: (LeaderLeaseExtensionObservationScope) -> Unit,
) : AutoCloseable {

    private val active = AtomicBoolean(true)
    private val contextElement by lazy(LazyThreadSafetyMode.PUBLICATION) {
        scopes.asContextElement(this)
    }

    /** 이 scope를 현재 blocking 실행 경계에 설치하고 이전 scope를 복원합니다. */
    @JvmSynthetic
    fun <T> withScope(block: () -> T): T {
        val previous = scopes.get()
        val installed = takeIf { isActive() }
        if (installed == null) {
            scopes.remove()
        } else {
            scopes.set(installed)
        }
        return try {
            block()
        } finally {
            if (previous == null) {
                scopes.remove()
            } else {
                scopes.set(previous)
            }
        }
    }

    /** coroutine 전환에서도 이 scope를 전파하고 이전 thread-local 값을 복원합니다. */
    @JvmSynthetic
    fun asContextElement(): ThreadContextElement<LeaderLeaseExtensionObservationScope?> = contextElement

    override fun close() {
        if (active.compareAndSet(true, false)) {
            closeAction(this)
        }
    }

    @JvmSynthetic
    internal fun isActive(): Boolean = active.get()

    override fun toString(): String = "LeaderLeaseExtensionObservationScope(<opaque>)"

    companion object {
        private val scopes = ThreadLocal<LeaderLeaseExtensionObservationScope?>()

        @JvmSynthetic
        internal fun currentOrNull(): LeaderLeaseExtensionObservationScope? =
            scopes.get()?.takeIf { it.isActive() }

        @JvmSynthetic
        internal fun create(
            observer: LeaderLeaseExtensionObserver,
            closeAction: (LeaderLeaseExtensionObservationScope) -> Unit,
        ): LeaderLeaseExtensionObservationScope =
            LeaderLeaseExtensionObservationScope(observer, closeAction)
    }
}
