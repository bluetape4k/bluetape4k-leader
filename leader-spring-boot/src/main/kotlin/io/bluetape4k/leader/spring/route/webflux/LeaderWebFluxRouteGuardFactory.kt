package io.bluetape4k.leader.spring.route.webflux

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.LeaderRouteEvaluation
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectFramework
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectPolicy
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadataProvider
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectResolver
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CancellationException

/**
 * `LeaderWebFluxRouteGuardFactory`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property runtime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property properties Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property evaluationScheduler blocking authority 평가를 실행할 scheduler입니다.
 */
class LeaderWebFluxRouteGuardFactory internal constructor(
    private val runtime: LeaderRouteAuthorityRuntime,
    private val properties: LeaderRouteGuardProperties,
    private val evaluationScheduler: Scheduler,
    private val redirectPolicy: LeaderRouteRedirectPolicy? =
        properties.redirect.takeIf { it.enabled }?.let(::LeaderRouteRedirectPolicy),
) {

    internal constructor(
        runtime: LeaderRouteAuthorityRuntime,
        properties: LeaderRouteGuardProperties,
        redirectPolicy: LeaderRouteRedirectPolicy?,
    ) : this(runtime, properties, Schedulers.boundedElastic(), redirectPolicy)

    internal constructor(
        runtime: LeaderRouteAuthorityRuntime,
        properties: LeaderRouteGuardProperties,
    ) : this(
        runtime,
        properties,
        Schedulers.boundedElastic(),
        properties.redirect.takeIf { it.enabled }?.let(::LeaderRouteRedirectPolicy),
    )

    /**
     * `filter` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun filter(slot: LeaderSlot): WebFilter = filterInternal(slot, null, null)

    /** resolver를 route owner가 직접 제공하는 additive overload입니다. */
    fun filter(slot: LeaderSlot, resolver: LeaderRouteRedirectResolver): WebFilter =
        filterInternal(slot, resolver, null)

    /** raw metadata provider를 포함한 redirect route filter입니다. */
    fun filter(
        slot: LeaderSlot,
        resolver: LeaderRouteRedirectResolver,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<ServerWebExchange>?,
    ): WebFilter = filterInternal(slot, resolver, metadataProvider)

    private fun filterInternal(
        slot: LeaderSlot,
        resolver: LeaderRouteRedirectResolver?,
        metadataProvider: LeaderRouteRedirectRequestMetadataProvider<ServerWebExchange>?,
    ): WebFilter = WebFilter { exchange, chain ->
        Mono.fromCallable {
            val evaluation = runtime.evaluateSnapshot(slot)
            val metadata = if (
                evaluation.decision == LeaderRouteDecision.NotLeader &&
                resolver != null &&
                redirectPolicy != null
            ) {
                metadataProvider?.capture(exchange)
            } else {
                null
            }
            val location = if (resolver != null && redirectPolicy != null) {
                redirectPolicy.redirect(slot, evaluation, resolver, metadata, LeaderRouteRedirectFramework.WEBFLUX)
            } else {
                null
            }
            RedirectResult(evaluation, location)
        }
            .subscribeOn(evaluationScheduler)
            .onErrorResume { failure ->
                val unwrapped = Exceptions.unwrap(failure)
                when (unwrapped) {
                    is CancellationException -> Mono.error(unwrapped)
                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        Mono.error(unwrapped)
                    }
                    is Error -> Mono.error(unwrapped)
                    else -> Mono.just(
                        RedirectResult(
                            LeaderRouteEvaluation(LeaderRouteDecision.Unavailable, null, java.time.Instant.now()),
                            null,
                        ),
                    )
                }
            }
            .flatMap { result ->
                if (result.evaluation.decision == LeaderRouteDecision.Allowed) {
                    chain.filter(exchange)
                } else if (result.location != null) {
                    exchange.response.statusCode = HttpStatusCode.valueOf(TEMPORARY_REDIRECT_STATUS)
                    exchange.response.headers.location = result.location
                    exchange.response.setComplete()
                } else {
                    exchange.response.statusCode = HttpStatusCode.valueOf(properties.rejectionStatus.value)
                    exchange.response.setComplete()
                }
            }
    }

    private data class RedirectResult(
        val evaluation: LeaderRouteEvaluation,
        val location: java.net.URI?,
    )

    private companion object {
        const val TEMPORARY_REDIRECT_STATUS = 307
    }
}
