package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteLeaseRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectPolicy
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadata
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadataProvider
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectResolver
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectFramework
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.AsyncHandlerInterceptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
    private val leaseRuntime: LeaderRouteLeaseRuntime? = null,
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
    fun interceptor(slot: LeaderSlot): HandlerInterceptor = object : HandlerInterceptor, AsyncHandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            _handler: Any,
        ): Boolean = preHandle(slot, request, response, null, null)

    override fun afterCompletion(
            request: HttpServletRequest,
            response: HttpServletResponse,
            _handler: Any,
            _exception: Exception?,
        ) {
            releaseLease(request)
        }

        override fun afterConcurrentHandlingStarted(
            request: HttpServletRequest,
            response: HttpServletResponse,
            handler: Any,
        ) {
            registerAsyncCompletion(request)
        }
    }

    /** resolver를 route owner가 직접 제공하는 additive overload입니다. */
    fun interceptor(slot: LeaderSlot, resolver: LeaderRouteRedirectResolver): HandlerInterceptor =
        interceptor(slot, resolver, null)

    /** raw metadata provider를 포함한 redirect route interceptor입니다. */
    fun interceptor(
        slot: LeaderSlot,
        resolver: LeaderRouteRedirectResolver,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>?,
    ): HandlerInterceptor = object : HandlerInterceptor, AsyncHandlerInterceptor {
        override fun preHandle(
            request: HttpServletRequest,
            response: HttpServletResponse,
            _handler: Any,
        ): Boolean {
            return preHandle(slot, request, response, resolver, metadataProvider)
        }

        override fun afterCompletion(
            request: HttpServletRequest,
            response: HttpServletResponse,
            _handler: Any,
        _exception: Exception?,
        ) {
            releaseLease(request)
        }

        override fun afterConcurrentHandlingStarted(
            request: HttpServletRequest,
            response: HttpServletResponse,
            handler: Any,
        ) {
            registerAsyncCompletion(request)
        }
    }

    @Suppress("ReturnCount")
    private fun preHandle(
        slot: LeaderSlot,
        request: HttpServletRequest,
        response: HttpServletResponse,
        resolver: LeaderRouteRedirectResolver?,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest>?,
    ): Boolean {
        val activeLeaseRuntime = leaseRuntime
        if (properties.authorityMode == io.bluetape4k.leader.spring.properties.LeaderRouteAuthorityMode.LEASE) {
            if (activeLeaseRuntime == null || !activeLeaseRuntime.acceptsAcquire()) {
                response.status = properties.rejectionStatus.value
                return false
            }
            when (val current = request.getAttribute(LEASE_HANDLE_ATTRIBUTE)) {
                null -> Unit
                is LeaseRequestHolder -> {
                    if (current.fingerprint == fingerprint(slot) && !current.terminal.get()) {
                        return true
                    }
                    activeLeaseRuntime.observe(io.bluetape4k.leader.spring.route.LeaseObservationCode.STALE)
                    // The request may be re-entered with a different slot. Release the
                    // previously published lease before rejecting the stale invocation.
                    releaseLease(request)
                    response.status = properties.rejectionStatus.value
                    return false
                }
                else -> {
                    activeLeaseRuntime.observe(io.bluetape4k.leader.spring.route.LeaseObservationCode.STALE)
                    response.status = properties.rejectionStatus.value
                    return false
                }
            }
            val handle = activeLeaseRuntime.tryAcquire(slot, mvcBlocking = true)
            if (handle == null) {
                response.status = properties.rejectionStatus.value
                return false
            }
            request.setAttribute(LEASE_HANDLE_ATTRIBUTE, LeaseRequestHolder(handle, fingerprint(slot)))
            return true
        }
        return handle(slot, request, response, resolver, metadataProvider)
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

    private fun releaseLease(request: HttpServletRequest) {
        (request.getAttribute(LEASE_HANDLE_ATTRIBUTE) as? LeaseRequestHolder)?.let { holder ->
            if (holder.terminal.compareAndSet(false, true)) {
                request.removeAttribute(LEASE_HANDLE_ATTRIBUTE)
                holder.handle.release()
            }
        }
    }

    private fun registerAsyncCompletion(request: HttpServletRequest) {
        val holder = request.getAttribute(LEASE_HANDLE_ATTRIBUTE) as? LeaseRequestHolder ?: return
        try {
            request.asyncContext.addListener(object : AsyncListener {
                override fun onComplete(event: AsyncEvent) = releaseLease(request)

                override fun onTimeout(event: AsyncEvent) = releaseLease(request)

                override fun onError(event: AsyncEvent) = releaseLease(request)

                override fun onStartAsync(event: AsyncEvent) {
                    event.asyncContext.addListener(this)
                }
            })
        } catch (_: IllegalStateException) {
            // Async registration failed after publication; roll back exactly once.
            releaseLease(request)
        } catch (_: IOException) {
            releaseLease(request)
        }
    }

    private fun fingerprint(slot: LeaderSlot): Int =
        FINGERPRINT_MULTIPLIER * slot.lockName.hashCode() + slot.leaderId.hashCode()

    private data class LeaseRequestHolder(
        val handle: LeaderLeaseHandle,
        val fingerprint: Int,
        val terminal: AtomicBoolean = AtomicBoolean(false),
    )

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
        const val FINGERPRINT_MULTIPLIER = 31
        const val LEASE_HANDLE_ATTRIBUTE = "io.bluetape4k.leader.spring.route.mvc.LEASE_HANDLE"
    }
}
