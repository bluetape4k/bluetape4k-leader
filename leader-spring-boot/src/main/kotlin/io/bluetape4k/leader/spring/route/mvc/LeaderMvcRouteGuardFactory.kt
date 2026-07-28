package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import java.util.concurrent.CancellationException

/**
 * `LeaderMvcRouteGuardFactory`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property runtime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property properties Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderMvcRouteGuardFactory internal constructor(
    private val runtime: LeaderRouteAuthorityRuntime,
    private val properties: LeaderRouteGuardProperties,
) {

    /**
     * `interceptor`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
     *
     * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
     */
    fun interceptor(slot: LeaderSlot): HandlerInterceptor = object : HandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            handler: Any,
        ): Boolean {
            val decision = try {
                runtime.evaluate(slot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (_: Exception) {
                LeaderRouteDecision.Unavailable
            }
            if (decision == LeaderRouteDecision.Allowed) {
                return true
            }

            response.status = properties.rejectionStatus.value
            return false
        }
    }
}
