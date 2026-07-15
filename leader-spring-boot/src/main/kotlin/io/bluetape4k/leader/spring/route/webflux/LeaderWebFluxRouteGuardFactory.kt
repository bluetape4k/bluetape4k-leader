package io.bluetape4k.leader.spring.route.webflux

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.WebFilter
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.concurrent.CancellationException

/** Creates opt-in, cancellation-aware WebFlux filters for leader-gated routes. */
class LeaderWebFluxRouteGuardFactory internal constructor(
    private val runtime: LeaderRouteAuthorityRuntime,
    private val properties: LeaderRouteGuardProperties,
) {

    /**
     * Creates a deferred filter for [slot]. Authority evaluation is offloaded
     * from Reactor non-blocking threads because leader state lookup is synchronous.
     */
    fun filter(slot: LeaderSlot): WebFilter = WebFilter { exchange, chain ->
        Mono.fromCallable { runtime.evaluate(slot) }
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorResume { failure ->
                when (failure) {
                    is CancellationException -> Mono.error(failure)
                    is InterruptedException -> {
                        Thread.currentThread().interrupt()
                        Mono.error(failure)
                    }
                    else -> Mono.just(LeaderRouteDecision.Unavailable)
                }
            }
            .flatMap { decision ->
                if (decision == LeaderRouteDecision.Allowed) {
                    chain.filter(exchange)
                } else {
                    exchange.response.statusCode = HttpStatusCode.valueOf(properties.rejectionStatus.value)
                    exchange.response.setComplete()
                }
            }
    }
}
