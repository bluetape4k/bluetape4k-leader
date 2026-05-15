package io.bluetape4k.leader.spring.boot

import io.bluetape4k.leader.LeaderLeaseAutoExtender
import kotlinx.atomicfu.atomic
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages the [LeaderLeaseAutoExtender] lifecycle within a Spring application context.
 *
 * ## Behavior / Contract
 * - [afterPropertiesSet]: registers this instance (once) with the JVM-scoped ref-count and calls
 *   [LeaderLeaseAutoExtender.restart] so the shared scheduler is running. Repeated calls on the
 *   same instance are idempotent — the global counter is incremented at most once per instance.
 * - [destroy]: unregisters this instance (once) and calls [LeaderLeaseAutoExtender.shutdown]
 *   **only when the last active context closes**. Repeated calls on the same instance are
 *   no-ops, preventing counter underflow.
 *
 * ## Ref-counting
 * The [activeContextCount] companion-object counter tracks how many `ApplicationContext`s currently
 * hold a live `LeaderLeaseAutoExtenderLifecycle` bean. Each instance contributes exactly 1 to the
 * count regardless of how many times its lifecycle callbacks are invoked. The [lifecycleLock] guards
 * the register/unregister sequences so that concurrent calls cannot produce a spurious zero.
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
