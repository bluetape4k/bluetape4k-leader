package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.ktor.statuspages.leaderElectionErrors
import io.bluetape4k.leader.ktor.statuspages.respondLeaderElectionError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class LeaderElectionStatusPagesAdapterTest {

    @Test
    fun `StatusPages 없이도 stable JSON fallback을 반환한다`() = runSuspendIO {
        testApplication {
            application {
                routing {
                    get("/error") {
                        call.respondLeaderElectionError(
                            toErrorContext(LeaderElectionErrorCode.NOT_LEADER),
                        )
                    }
                }
            }

            val response = client.get("/error")
            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldBeEqualTo
                """{"code":"NOT_LEADER","message":"leader state does not allow this request","status":503}"""
        }
    }

    @Test
    fun `StatusPages adapter는 예외를 같은 payload로 변환한다`() = runSuspendIO {
        testApplication {
            application {
                install(StatusPages) { leaderElectionErrors() }
                routing {
                    get("/error") {
                        throw LeaderElectionHttpException(
                            toErrorContext(LeaderElectionErrorCode.BACKEND_UNAVAILABLE),
                        )
                    }
                }
            }

            val response = client.get("/error")
            response shouldHaveStatus HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "\"code\":\"BACKEND_UNAVAILABLE\""
        }
    }
}
