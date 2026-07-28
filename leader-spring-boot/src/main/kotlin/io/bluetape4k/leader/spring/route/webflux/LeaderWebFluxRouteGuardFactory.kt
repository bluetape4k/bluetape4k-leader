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

/**
 * `LeaderWebFluxRouteGuardFactory`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property runtime Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property properties Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LeaderWebFluxRouteGuardFactory internal constructor(
    private val runtime: LeaderRouteAuthorityRuntime,
    private val properties: LeaderRouteGuardProperties,
) {

    /**
     * `filter` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
