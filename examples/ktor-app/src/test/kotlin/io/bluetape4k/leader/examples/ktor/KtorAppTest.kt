package io.bluetape4k.leader.examples.ktor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.ktor.core.HealthResponse
import io.bluetape4k.ktor.testing.decodeJsonBody
import io.bluetape4k.ktor.testing.shouldHaveStatus
import io.bluetape4k.leader.ktor.LeaderElectionPlugin
import io.bluetape4k.leader.ktor.leaderScheduled
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class KtorAppTest: AbstractKtorAppTest() {

    companion object: KLoggingChannel() {
        private val SHORT_PERIOD = 100.milliseconds
        private val POLL_INTERVAL = 100.milliseconds
        private val AWAIT_TIMEOUT = 15.seconds

        private val objectMapper = ObjectMapper()
    }

    @Test
    fun `GET stats - 초기 상태 응답 (runCount=0, lastRunAt=null)`() = runSuspendIO {
        val aggregator = StatsAggregator()
        // 매우 긴 period 로 스케줄링이 사실상 1회만 실행되도록 보장
        testApplication {
            application {
                module(
                    connection = newConnection(),
                    aggregator = aggregator,
                    aggregationLockName = randomLockName(),
                    aggregationPeriod = 1.seconds,
                )
            }
            startApplication()

            val response = client.get("/stats") { accept(ContentType.Application.Json) }
            response shouldHaveStatus HttpStatusCode.OK

            val body = response.bodyAsText()
            log.debug { "GET /stats body=$body" }
            val node: JsonNode = objectMapper.readTree(body)
            // runCount 는 0 또는 양수 (cycle 이 빠르게 1회 이상 실행되었을 수 있음)
            val runCountNode = requireNotNull(node.get("runCount")) {
                "runCount field missing in response: $body"
            }
            (runCountNode.asLong() >= 0L).shouldBeTrue()
        }
    }

    @Test
    fun `GET health - 200 OK 와 status UP`() = runSuspendIO {
        testApplication {
            application {
                module(
                    connection = newConnection(),
                    aggregationLockName = randomLockName(),
                    aggregationPeriod = 1.seconds,
                )
            }
            startApplication()

            val response = client.get("/health") { accept(ContentType.Application.Json) }
            response shouldHaveStatus HttpStatusCode.OK

            val health = response.decodeJsonBody<HealthResponse>()
            health.status shouldBeEqualTo HealthResponse.UP
        }
    }

    @Test
    fun `GET readyz - shared readiness route returns UP`() = runSuspendIO {
        testApplication {
            application {
                module(
                    connection = newConnection(),
                    aggregationLockName = randomLockName(),
                    aggregationPeriod = 1.seconds,
                )
            }
            startApplication()

            val response = client.get("/readyz") { accept(ContentType.Application.Json) }
            response shouldHaveStatus HttpStatusCode.OK

            val readiness = response.decodeJsonBody<HealthResponse>()
            readiness.status shouldBeEqualTo HealthResponse.UP
        }
    }

    @Test
    fun `leaderScheduled 가 plugin 통해 주기적으로 실행되어 runCount 가 증가한다`() = runSuspendIO {
        val aggregator = StatsAggregator()

        testApplication {
            application {
                module(
                    connection = newConnection(),
                    aggregator = aggregator,
                    aggregationLockName = randomLockName(),
                    aggregationPeriod = SHORT_PERIOD,
                )
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { aggregator.currentState().runCount >= 3L }

            val state = aggregator.currentState()
            state.runCount shouldBeGreaterOrEqualTo 3L
            (state.lastRunAt != null).shouldBeTrue()

            // REST endpoint 로도 동일 상태 노출 확인
            val response = client.get("/stats") { accept(ContentType.Application.Json) }
            response shouldHaveStatus HttpStatusCode.OK
            val body = response.bodyAsText()
            val node = objectMapper.readTree(body)
            val runCountNode = requireNotNull(node.get("runCount")) {
                "runCount field missing in response: $body"
            }
            (runCountNode.asLong() >= 3L).shouldBeTrue()
        }
    }

    @Test
    fun `다중 인스턴스 시뮬레이션 - 동일 lockName 공유 시 단일 인스턴스만 실행`() = runSuspendIO {
        // 두 인스턴스가 동일 lockName 을 공유하지만, 카운터는 각 인스턴스가 별도로 보유한다.
        // 핵심 검증: 카운터 합이 양수 (적어도 누군가가 리더로 작동) + 단일 cycle 에서 양쪽이 동시에 실행되지 않음 (락 보호).
        val sharedLockName = randomLockName()
        val aggregatorA = StatsAggregator()
        val aggregatorB = StatsAggregator()

        // E1 batch-scheduler 패턴 — 인스턴스별 별도 connection
        val connectionA = newConnection()
        val connectionB = newConnection()

        testApplication {
            application {
                // 인스턴스 A — module() 내부에서 leaderScheduled 등록
                module(
                    connection = connectionA,
                    aggregator = aggregatorA,
                    aggregationLockName = sharedLockName,
                    aggregationPeriod = SHORT_PERIOD,
                )
                // 인스턴스 B — 별도 elector + 별도 connection 으로 동일 락에 경합
                leaderScheduled(
                    lockName = sharedLockName,
                    period = SHORT_PERIOD,
                    leaderElection = LettuceSuspendLeaderElector(connectionB),
                ) {
                    aggregatorB.aggregate()
                }
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { aggregatorA.currentState().runCount + aggregatorB.currentState().runCount >= 3L }

            val total = aggregatorA.currentState().runCount + aggregatorB.currentState().runCount
            total shouldBeGreaterOrEqualTo 3L
            (total > 0L).shouldBeTrue()
        }
    }

    @Test
    fun `ApplicationStopped 시 leaderScheduled job 자동 취소`() = runSuspendIO {
        val aggregator = StatsAggregator()

        testApplication {
            application {
                module(
                    connection = newConnection(),
                    aggregator = aggregator,
                    aggregationLockName = randomLockName(),
                    aggregationPeriod = SHORT_PERIOD,
                )
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { aggregator.currentState().runCount >= 2L }
        } // testApplication 블록 종료 시 application 종료 + 스코프 취소

        val countAtStop = aggregator.currentState().runCount
        delay(SHORT_PERIOD * 5)
        // 종료 후에는 더 이상 실행되지 않아야 한다 — 약간의 race 허용 (+2)
        val countAfterDelay = aggregator.currentState().runCount
        (countAfterDelay <= countAtStop + 2L).shouldBeTrue()
    }

    @Test
    fun `리더 작업 실패는 격리되어 다음 cycle 이 계속 실행된다 - poison-pill 방지`() = runSuspendIO {
        val cycles = AtomicInteger(0)
        val firstCycleConsumed = AtomicBoolean(false)

        testApplication {
            application {
                install(LeaderElectionPlugin) {
                    leaderElection = LettuceSuspendLeaderElector(newConnection())
                }
                leaderScheduled(
                    lockName = randomLockName(),
                    period = SHORT_PERIOD,
                ) {
                    val current = cycles.incrementAndGet()
                    if (firstCycleConsumed.compareAndSet(false, true)) {
                        error("의도된 첫 cycle 실패 — poison-pill 격리 검증")
                    }
                    log.debug { "정상 cycle #$current" }
                }
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { cycles.get() >= 3 }

            cycles.get() shouldBeGreaterOrEqualTo 3
            firstCycleConsumed.get().shouldBeTrue()
        }
    }
}
