package io.bluetape4k.leader.spring.boot

import io.bluetape4k.leader.LeaderLeaseAutoExtender
import kotlinx.atomicfu.atomic
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `LeaderLeaseAutoExtenderLifecycle`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property watchdogThreads Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property watchdogAsyncExtend Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderLeaseAutoExtenderLifecycle(
    private val watchdogThreads: Int? = null,
    private val watchdogAsyncExtend: Boolean = false,
) : InitializingBean, DisposableBean {

    private val registered = atomic(false)

    companion object {
        internal val activeContextCount = atomic(0)

        // Guards the register-then-restart / unregister-then-shutdown sequences so that
        // a concurrent destroy() cannot slip in between a decrement reaching zero and the
        // actual shutdown() call while another afterPropertiesSet() has already incremented
        // the count back above zero. ReentrantLock avoids virtual-thread pinning.
        private val lifecycleLock = ReentrantLock()
    }

    override fun afterPropertiesSet() {
        lifecycleLock.withLock {
            if (registered.compareAndSet(false, true)) {
                activeContextCount.incrementAndGet()
                // Only configure when the user has explicitly set at least one non-default value.
                // Skipping here when both are defaults avoids overwriting settings that were
                // established by an earlier Spring context in the same JVM (multi-context safety).
                if (watchdogThreads != null || watchdogAsyncExtend) {
                    LeaderLeaseAutoExtender.configure(
                        watchdogThreads = watchdogThreads ?: LeaderLeaseAutoExtender.watchdogThreadCount(),
                        asyncExtend = watchdogAsyncExtend,
                    )
                }
            }
            LeaderLeaseAutoExtender.restart()
        }
    }

    override fun destroy() {
        lifecycleLock.withLock {
            if (registered.compareAndSet(true, false)) {
                if (activeContextCount.decrementAndGet() == 0) {
                    LeaderLeaseAutoExtender.shutdown()
                }
            }
        }
    }
}
