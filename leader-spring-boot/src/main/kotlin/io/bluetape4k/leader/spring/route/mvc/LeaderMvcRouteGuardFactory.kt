package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import java.util.concurrent.CancellationException

/** Creates opt-in Spring MVC interceptors for leader-gated routes. */
class LeaderMvcRouteGuardFactory internal constructor(
    private val runtime: LeaderRouteAuthorityRuntime,
    private val properties: LeaderRouteGuardProperties,
) {

    /**
     * Creates an interceptor for [slot]. Register it only for paths that require
     * passive leader-route authorization.
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
