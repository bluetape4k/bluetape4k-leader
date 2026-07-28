package io.bluetape4k.leader.examples.ktor

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.bluetape4k.ktor.core.Bluetape4kKtorCoreConfig
import io.bluetape4k.ktor.core.installBluetape4kKtorCore
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
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * `KtorAppMain`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
object KtorAppMain: KLogging() {

    /**
     * `ENV_REDIS_URL` 값은 example workflow 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val ENV_REDIS_URL: String = "REDIS_URL"

    /**
     * `ENV_PORT` 값은 example workflow 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val ENV_PORT: String = "PORT"

    /**
     * `DEFAULT_REDIS_URL` 값은 example workflow 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val DEFAULT_REDIS_URL: String = "redis://localhost:6379"

    /**
     * `DEFAULT_PORT` 값은 example workflow 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val DEFAULT_PORT: Int = 8080

    /**
     * `DEFAULT_AGGREGATION_LOCK` 값은 example workflow 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    const val DEFAULT_AGGREGATION_LOCK: String = "hourly-stats-aggregation"

    /**
     * `DEFAULT_AGGREGATION_PERIOD` 값은 example workflow 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val DEFAULT_AGGREGATION_PERIOD: Duration = 60.minutes

    @JvmStatic
    fun main(args: Array<String>) {
        val redisUrl = System.getenv(ENV_REDIS_URL) ?: DEFAULT_REDIS_URL
        val port = System.getenv(ENV_PORT)?.toIntOrNull() ?: DEFAULT_PORT
        log.info { "KtorAppMain 시작 — redisUrl=${redactRedisUrlForLog(redisUrl)}, port=$port" }

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

internal fun redactRedisUrlForLog(redisUrl: String): String =
    runCatching {
        val uri = URI(redisUrl)
        if (uri.userInfo == null) {
            redisUrl
        } else {
            URI(uri.scheme, "redacted", uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }
    }.getOrElse { "<invalid-redis-url>" }

/**
 * `Application` 호출은 example workflow 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
    installBluetape4kKtorCore(
        Bluetape4kKtorCoreConfig(
            installContentNegotiation = false,
            installStatusPages = false,
            healthPath = "/health",
        )
    )

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
        statsRoutes(aggregator)
    }
}
