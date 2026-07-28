package io.bluetape4k.leader.examples.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `Route` 호출은 example workflow 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun Route.statsRoutes(aggregator: StatsAggregator) {
    get("/stats") {
        call.respond(HttpStatusCode.OK, aggregator.currentState())
    }
}
