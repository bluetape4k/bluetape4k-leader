package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.LeaderManagementActionRegistry
import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.DisposableBean

/**
 * library-owned action registry를 Spring context보다 먼저 bounded하게 drain합니다.
 *
 * custom registry에는 이 lifecycle을 연결하지 않습니다. 애플리케이션이 소유한
 * registry와 scheduler의 종료 순서는 애플리케이션이 직접 결정해야 합니다.
 */
class LeaderManagementActionLifecycle(
    private val registry: LeaderManagementActionRegistry,
): DisposableBean {

    private companion object: KLogging()

    override fun destroy() {
        val drained = runCatching {
            registry.closeAndDrain()
        }.getOrElse {
            log.warn("leader management action registry drain failed; continuing shutdown")
            false
        }
        if (!drained) {
            log.warn("leader management action registry drain timed out; continuing shutdown")
        }
        runCatching {
            registry.close()
        }.onFailure {
            log.warn("leader management action registry close failed; continuing shutdown")
        }
    }
}
