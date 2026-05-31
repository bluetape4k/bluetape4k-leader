package io.bluetape4k.leader.examples.ktor

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.bluetape4k.ktor.core.bluetape4kHealthRoutes
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.ktor.LeaderElectionPlugin
import io.bluetape4k.leader.ktor.leaderScheduled
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.closeSafe
import io.bluetape4k.utils.ShutdownQueue
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * `examples/ktor-app` 의 main 진입점.
 *
 * ## 동작/계약
 *
 * - 환경 변수 [ENV_REDIS_URL] (`REDIS_URL`) 로 Redis 주소를 받고, 미설정 시 [DEFAULT_REDIS_URL] 사용.
 * - 환경 변수 [ENV_PORT] (`PORT`) 로 listen 포트를 받고, 미설정 시 [DEFAULT_PORT] (8080) 사용.
 *   다중 인스턴스 데모(예: `PORT=8081`) 시 collision 회피.
 * - JVM 종료 시 Lettuce [RedisClient] + [StatefulRedisConnection] 을 [ShutdownQueue] 로 정리한다.
 * - 백그라운드로 [DEFAULT_AGGREGATION_LOCK] 락 이름의 시간별 통계 집계 작업을 [DEFAULT_AGGREGATION_PERIOD]
 *   주기로 실행한다 — 다중 인스턴스 환경에서 단 하나의 인스턴스만 cycle 마다 실행한다.
 * - leaderElection 의 `leaseTime` 은 `aggregationPeriod * 2` 로 설정되며, `minLeaseTime` 은
 *   `aggregationPeriod` 와 동일하게 설정되어 다음 cycle 까지 lock 을 보유한다.
 *   짧은 작업이 빨리 끝나도 다른 replica 가 같은 cycle 의 작업을 중복 실행하지 않도록 보장.
 * - 본 객체는 main 진입점 + 모듈 함수만 노출한다. 테스트는 [module] 을 직접 호출하여 testApplication 에 결합한다.
 */
object KtorAppMain: KLogging() {

    /** Redis 접속 URL 환경 변수 이름. */
    const val ENV_REDIS_URL: String = "REDIS_URL"

    /** Ktor 서버 listen 포트 환경 변수 이름. 다중 인스턴스 데모 시 사용. */
    const val ENV_PORT: String = "PORT"

    /** [ENV_REDIS_URL] 미설정 시 사용할 기본 Redis URL. */
    const val DEFAULT_REDIS_URL: String = "redis://localhost:6379"

    /** [ENV_PORT] 미설정 시 사용할 Ktor 서버 listen 기본 포트. */
    const val DEFAULT_PORT: Int = 8080

    /** 시간별 통계 집계 작업의 락 이름. */
    const val DEFAULT_AGGREGATION_LOCK: String = "hourly-stats-aggregation"

    /** 시간별 통계 집계 작업의 기본 cycle 주기. */
    val DEFAULT_AGGREGATION_PERIOD: Duration = 60.minutes

    @JvmStatic
    fun main(args: Array<String>) {
        val redisUrl = System.getenv(ENV_REDIS_URL) ?: DEFAULT_REDIS_URL
        val port = System.getenv(ENV_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        log.info { "KtorAppMain 시작 — redisUrl=$redisUrl, port=$port" }

        val client = RedisClient.create(redisUrl).also {
            ShutdownQueue.register { runCatching { it.shutdown() } }
        }
        val connection = client.connect(StringCodec.UTF8).also {
            ShutdownQueue.register { it.closeSafe() }
        }

        embeddedServer(CIO, port = port) {
            module(connection)
        }.start(wait = true)
    }
}

/**
 * Ktor 애플리케이션 모듈 — 플러그인 install + 라우트 + 백그라운드 leader scheduled job 을 구성한다.
 *
 * ## 동작/계약
 *
 * - [ContentNegotiation] + Jackson 으로 JSON 직렬화 활성화.
 * - [LeaderElectionPlugin] 설치 — [SuspendLeaderElector] 인스턴스 주입.
 * - [Application.leaderScheduled] 로 시간별 통계 집계 백그라운드 잡을 등록 — `ApplicationStopped` 시 자동 취소.
 * - `/stats`, `/health`, `/readyz` 라우트를 등록한다.
 *
 * ## 리더 선출 옵션 — leaseTime / minLeaseTime
 *
 * 짧은 [aggregationPeriod] 사용 시 작업이 빨리 끝나면 lock 이 즉시 해제되어 다음 cycle 에서
 * 다른 replica 가 같은 작업을 중복 실행할 수 있다. 이를 방지하기 위해:
 * - `leaseTime = aggregationPeriod * 2` — auto-extend 미사용 환경에서 안전 마진 확보
 * - `minLeaseTime = aggregationPeriod` — 작업 완료 후에도 다음 cycle 까지 lock 보유
 *
 * @param connection 리더 선출 백엔드로 사용할 Lettuce [StatefulRedisConnection] (StringCodec).
 * @param aggregator 통계 집계 도메인. 테스트에서 fake/spy 주입 목적으로 외부 주입 허용 (기본값: 새 인스턴스).
 * @param aggregationLockName 리더 선출에 사용할 락 이름 (기본 [KtorAppMain.DEFAULT_AGGREGATION_LOCK]).
 * @param aggregationPeriod cycle 주기 (기본 [KtorAppMain.DEFAULT_AGGREGATION_PERIOD]).
 */
fun Application.module(
    connection: StatefulRedisConnection<String, String>,
    aggregator: StatsAggregator = StatsAggregator(),
    aggregationLockName: String = KtorAppMain.DEFAULT_AGGREGATION_LOCK,
    aggregationPeriod: Duration = KtorAppMain.DEFAULT_AGGREGATION_PERIOD,
) {
    install(ContentNegotiation) {
        jackson {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }

    val electorOptions = LeaderElectionOptions(
        // 비리더 인스턴스가 즉시 skip 하도록 짧은 wait 사용 (테스트의 짧은 period 와 호환)
        waitTime = aggregationPeriod,
        // auto-extend 미사용 — period 의 2배로 안전 마진 확보
        leaseTime = aggregationPeriod * 2,
        // period 동안 lock 보유 → 다음 cycle 에서 다른 replica 가 같은 작업 중복 실행 차단
        minLeaseTime = aggregationPeriod,
    )

    install(LeaderElectionPlugin) {
        leaderElection = LettuceSuspendLeaderElector(connection, electorOptions)
    }

    leaderScheduled(aggregationLockName, period = aggregationPeriod) {
        aggregator.aggregate()
    }

    routing {
        bluetape4kHealthRoutes(healthPath = "/health")
        statsRoutes(aggregator)
    }
}
