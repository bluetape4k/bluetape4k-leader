package io.bluetape4k.leader.spring.boot

import io.bluetape4k.leader.LeaderLeaseAutoExtender
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the [LeaderLeaseAutoExtender] lifecycle within a Spring application context.
 *
 * ## Behavior / Contract
 * - [afterPropertiesSet]: increments the active-context counter and calls [LeaderLeaseAutoExtender.restart]
 *   so the shared JVM-scoped scheduler is running. No-op when already running.
 * - [destroy]: decrements the counter and calls [LeaderLeaseAutoExtender.shutdown] **only when the last
 *   active context closes**. This prevents one context from stopping the global scheduler while other
 *   contexts in the same JVM are still using it (e.g. parallel `@DirtiesContext` test suites or
 *   multi-context application deployments).
 *
 * ## Ref-counting
 * The [activeContextCount] companion-object counter tracks how many `ApplicationContext`s currently
 * hold a live `LeaderLeaseAutoExtenderLifecycle` bean. [destroy] decrements first; only the caller
 * that drives the counter to zero proceeds with [LeaderLeaseAutoExtender.shutdown].
 */
class LeaderLeaseAutoExtenderLifecycle : InitializingBean, DisposableBean {

    companion object {
        internal val activeContextCount = AtomicInteger(0)
    }

    override fun afterPropertiesSet() {
        activeContextCount.incrementAndGet()
        LeaderLeaseAutoExtender.restart()
    }

    override fun destroy() {
        if (activeContextCount.decrementAndGet() == 0) {
            LeaderLeaseAutoExtender.shutdown()
        }
    }
}
