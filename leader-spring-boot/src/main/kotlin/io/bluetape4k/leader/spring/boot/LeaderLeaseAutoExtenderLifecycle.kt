package io.bluetape4k.leader.spring.boot

import io.bluetape4k.leader.LeaderLeaseAutoExtender
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean

/**
 * Manages the [LeaderLeaseAutoExtender] lifecycle within a Spring application context.
 *
 * ## Behavior / Contract
 * - [afterPropertiesSet]: calls [LeaderLeaseAutoExtender.restart] so the shared JVM-scoped
 *   scheduler is running when the context starts. No-op when already running.
 * - [destroy]: calls [LeaderLeaseAutoExtender.shutdown] to drain in-flight ticks (up to 5 s)
 *   and stop the scheduler when the context closes.
 * - Both methods are idempotent and safe for multi-context JVM scenarios (e.g. `@DirtiesContext`
 *   in tests or sequential Spring context restarts in the same classloader).
 */
class LeaderLeaseAutoExtenderLifecycle : InitializingBean, DisposableBean {
    override fun afterPropertiesSet() {
        LeaderLeaseAutoExtender.restart()
    }

    override fun destroy() {
        LeaderLeaseAutoExtender.shutdown()
    }
}
