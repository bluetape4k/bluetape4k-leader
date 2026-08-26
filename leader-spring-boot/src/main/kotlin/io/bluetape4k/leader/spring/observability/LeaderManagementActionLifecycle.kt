package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderManagementActionRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean

/**
 * library-owned action registry를 Spring context보다 먼저 bounded하게 drain합니다.
 *
 * custom registry에는 이 lifecycle을 연결하지 않습니다. 애플리케이션이 소유한
 * registry와 scheduler의 종료 순서는 애플리케이션이 직접 결정해야 합니다.
 */
class LeaderManagementActionLifecycle(
    private val registry: LeaderManagementActionRegistry,
) : DisposableBean {

    override fun destroy() {
        val drained = runCatching { registry.closeAndDrain() }
            .getOrElse {
                logger.warn("leader management action registry drain failed; continuing shutdown")
                false
            }
        if (!drained) {
            logger.warn("leader management action registry drain timed out; continuing shutdown")
        }
        runCatching { registry.close() }
            .onFailure { logger.warn("leader management action registry close failed; continuing shutdown") }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(LeaderManagementActionLifecycle::class.java)
    }
}
