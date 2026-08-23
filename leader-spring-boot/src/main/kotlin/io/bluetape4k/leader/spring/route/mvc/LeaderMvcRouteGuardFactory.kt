package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectPolicy
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadata
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadataProvider
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectResolver
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectFramework
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

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
    private val redirectPolicy: LeaderRouteRedirectPolicy? =
        properties.redirect.takeIf { it.enabled }?.let(::LeaderRouteRedirectPolicy),
) {

    internal constructor(
        runtime: LeaderRouteAuthorityRuntime,
        properties: LeaderRouteGuardProperties,
    ) : this(runtime, properties, properties.redirect.takeIf { it.enabled }?.let(::LeaderRouteRedirectPolicy))

    /**
     * `interceptor`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
     *
     * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
     */
    fun interceptor(slot: LeaderSlot): HandlerInterceptor = object : HandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            _handler: Any,
        ): Boolean = handle(slot, request, response, null, null)
    }

    /** resolver를 route owner가 직접 제공하는 additive overload입니다. */
    fun interceptor(slot: LeaderSlot, resolver: LeaderRouteRedirectResolver): HandlerInterceptor =
        interceptor(slot, resolver, null)

    /** raw metadata provider를 포함한 redirect route interceptor입니다. */
    fun interceptor(
        slot: LeaderSlot,
        resolver: LeaderRouteRedirectResolver,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>?,
    ): HandlerInterceptor = object : HandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            _handler: Any,
        ): Boolean {
            return handle(slot, request, response, resolver, metadataProvider)
        }
    }

    private fun handle(
        slot: LeaderSlot,
        request: HttpServletRequest,
        response: HttpServletResponse,
        resolver: LeaderRouteRedirectResolver?,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>?,
    ): Boolean {
        val evaluation = runtime.evaluateSnapshot(slot)
        if (evaluation.decision == LeaderRouteDecision.Allowed) return true

        val location = if (resolver != null && redirectPolicy != null) {
            val metadataCapture = if (evaluation.decision == LeaderRouteDecision.NotLeader) {
                captureMetadata(request, metadataProvider)
            } else {
                MetadataCapture.Success(null)
            }
            when (metadataCapture) {
                MetadataCapture.Failure -> null
                is MetadataCapture.Success -> redirectPolicy.redirect(
                    slot,
                    evaluation,
                    resolver,
                    metadataCapture.metadata,
                    LeaderRouteRedirectFramework.MVC,
                )
            }
        } else {
            null
        }
        return if (location != null) {
            response.status = TEMPORARY_REDIRECT_STATUS
            response.setHeader("Location", location.toASCIIString())
            false
        } else {
            response.status = properties.rejectionStatus.value
            false
        }
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun captureMetadata(
        request: HttpServletRequest,
        provider: LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>?,
    ): MetadataCapture = try {
        MetadataCapture.Success(provider?.capture(request))
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (_: Exception) {
        MetadataCapture.Failure
    }

    private sealed interface MetadataCapture {
        data class Success(val metadata: LeaderRouteRedirectRequestMetadata?) : MetadataCapture

        data object Failure : MetadataCapture
    }

    private companion object {
        const val TEMPORARY_REDIRECT_STATUS = 307
    }
}
