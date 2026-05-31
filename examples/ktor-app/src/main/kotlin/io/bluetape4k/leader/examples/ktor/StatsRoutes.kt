package io.bluetape4k.leader.examples.ktor

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * [StatsAggregator] 의 현재 상태를 노출하는 REST 라우트.
 *
 * ## 동작/계약
 *
 * - `GET /stats` — [StatsAggregator.currentState] 결과를 JSON 으로 반환 (200 OK).
 * - 범용 health/readiness endpoint 는 bluetape4k Ktor core installer 로 등록한다.
 * - JSON 직렬화는 [ContentNegotiation] + Jackson 으로 처리되므로, 본 모듈에서는 [respond] 만 호출한다.
 *
 * ```kotlin
 * fun Application.configureRoutes(aggregator: StatsAggregator) {
 *     install(ContentNegotiation) { jackson() }
 *     routing { statsRoutes(aggregator) }
 * }
 * ```
 *
 * @param aggregator REST 응답 데이터의 출처 — 동일 [Application] 라이프사이클 안에서 공유한다.
 */
fun Route.statsRoutes(aggregator: StatsAggregator) {
    get("/stats") {
        call.respond(HttpStatusCode.OK, aggregator.currentState())
    }
}
