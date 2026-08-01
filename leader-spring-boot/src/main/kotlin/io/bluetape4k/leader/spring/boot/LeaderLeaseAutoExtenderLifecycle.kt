package io.bluetape4k.leader.spring.boot

import io.bluetape4k.leader.LeaderLeaseAutoExtender
import kotlinx.atomicfu.atomic
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `LeaderLeaseAutoExtenderLifecycle`는 Spring Boot integration의 leader election,
 * route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property watchdogThreads Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property watchdogAsyncExtend Spring Boot integration 계약에서 사용하는 속성입니다.
 *
 * JVM-global scheduler는 live Spring context 전체가 공유합니다. 첫 번째 명시 설정이 ownership을 가지며,
 * 다른 live context의 상충하는 명시 설정은 기존 scheduler를 변경하기 전에 거부됩니다.
 */
class LeaderLeaseAutoExtenderLifecycle(
    private val watchdogThreads: Int? = null,
    private val watchdogAsyncExtend: Boolean? = null,
) : InitializingBean, DisposableBean {

    /** Preserves the `(Integer, boolean)` constructor published in 0.4.0. */
    constructor(watchdogThreads: Int?, watchdogAsyncExtend: Boolean) : this(
        watchdogThreads = watchdogThreads,
        watchdogAsyncExtend = watchdogAsyncExtend as Boolean?,
    )

    /** Preserves Kotlin's published primitive-boolean default-constructor descriptor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        watchdogThreads: Int?,
        watchdogAsyncExtend: Boolean,
        mask: Int,
        marker: kotlin.jvm.internal.DefaultConstructorMarker?,
    ) : this(
        watchdogThreads = if (mask and 0x001 != 0) null else watchdogThreads,
        watchdogAsyncExtend = (if (mask and 0x002 != 0) false else watchdogAsyncExtend) as Boolean?,
    )

    private val registered = atomic(false)

    companion object {
        internal val activeContextCount = atomic(0)
        internal var activeConfiguration: Configuration? = null

        // Guards the register-then-restart / unregister-then-shutdown sequences so that
        // a concurrent destroy() cannot slip in between a decrement reaching zero and the
        // actual shutdown() call while another afterPropertiesSet() has already incremented
        // the count back above zero. ReentrantLock avoids virtual-thread pinning.
        private val lifecycleLock = ReentrantLock()
    }

    override fun afterPropertiesSet() {
        lifecycleLock.withLock {
            if (registered.compareAndSet(false, true)) {
                try {
                    val requested = requestedConfiguration()
                    val owned = activeConfiguration
                    check(requested == null || owned == null || requested == owned) {
                        "Conflicting LeaderLeaseAutoExtender configuration across live Spring contexts: " +
                                "active=$owned, requested=$requested"
                    }
                    if (requested != null && owned == null) {
                        LeaderLeaseAutoExtender.configure(
                            watchdogThreads = requested.watchdogThreads,
                            asyncExtend = requested.asyncExtend,
                        )
                        activeConfiguration = requested
                    }
                    activeContextCount.incrementAndGet()
                } catch (e: IllegalArgumentException) {
                    registered.value = false
                    throw e
                } catch (e: IllegalStateException) {
                    registered.value = false
                    throw e
                }
            }
            LeaderLeaseAutoExtender.restart()
        }
    }

    override fun destroy() {
        lifecycleLock.withLock {
            if (registered.compareAndSet(true, false)) {
                if (activeContextCount.decrementAndGet() == 0) {
                    activeConfiguration = null
                    LeaderLeaseAutoExtender.shutdown()
                }
            }
        }
    }

    private fun requestedConfiguration(): Configuration? =
        if (watchdogThreads != null || watchdogAsyncExtend != null) {
            Configuration(
                watchdogThreads = watchdogThreads ?: LeaderLeaseAutoExtender.watchdogThreadCount(),
                asyncExtend = watchdogAsyncExtend ?: false,
            )
        } else {
            null
        }

    internal data class Configuration(
        val watchdogThreads: Int,
        val asyncExtend: Boolean,
    )
}
