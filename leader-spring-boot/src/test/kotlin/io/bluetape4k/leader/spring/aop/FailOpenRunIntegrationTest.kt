package io.bluetape4k.leader.spring.aop

import eu.rekawek.toxiproxy.ToxiproxyClient
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.annotation.LeaderElection
import io.bluetape4k.leader.lettuce.LettuceLeaderElectorFactory
import io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties
import io.bluetape4k.leader.spring.aop.spel.SpelExpressionEvaluator
import io.bluetape4k.leader.spring.aop.util.LockNameValidator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.infra.ToxiproxyServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.Network
import java.time.Duration

/**
 * [LeaderElectionAspect] FAIL_OPEN_RUN Lettuce + ToxiProxy 통합 테스트.
 *
 * ## 검증 시나리오
 * 1. **경쟁(Contention)**: Redis 락을 외부에서 점유 후 Aspect 호출 → `Skipped` → FAIL_OPEN_RUN → 본문 실행
 * 2. **백엔드 오류(ToxiProxy)**: ToxiProxy 프록시 삭제로 Redis 접근 차단 → FAIL_OPEN_RUN → 본문 실행
 * 3. **경쟁 없음(정상)**: 락 미점유 상태 → `Elected` 경로 → 본문 실행
 *
 * ## CTW 제한
 * Freefair CTW는 main sourceSet 에만 적용되므로 test 클래스에는 AOP 인터셉션이 없다.
 * [LeaderElectionAspect.aroundLeader]를 직접 호출하여 실제 Lettuce backend 와 통합 검증.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailOpenRunIntegrationTest {

    companion object : KLogging() {
        private const val RESULT = "lettuce-result"
        private const val CONTENTION_LOCK = "fail-open-it-contention"
        private const val BACKEND_LOCK = "fail-open-it-backend"

        private val redis = RedisServer.Launcher.redis

        private val client: RedisClient by lazy {
            RedisClient.create(redis.url).also {
                ShutdownQueue.register { runCatching { it.shutdown() } }
            }
        }

        private val connection: StatefulRedisConnection<String, String> by lazy {
            client.connect(StringCodec.UTF8).also {
                ShutdownQueue.register { it.closeSafe() }
            }
        }
    }

    // ── 경쟁 시나리오용 ──
    private class ContentionService {
        @LeaderElection(name = CONTENTION_LOCK, failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        fun execute(): String? = RESULT
    }

    // ── ToxiProxy 백엔드 오류 시나리오용 ──
    private class BackendErrorService {
        @LeaderElection(name = BACKEND_LOCK, failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        fun execute(): String? = RESULT
    }

    // ── 정상 경로 검증용 ──
    private class NormalService {
        @LeaderElection(name = "fail-open-it-normal", failureMode = LeaderAspectFailureMode.FAIL_OPEN_RUN)
        fun execute(): String? = RESULT
    }

    private val signature: MethodSignature = mockk()
    private val pjp: ProceedingJoinPoint = mockk()
    private val beanSelector: LeaderBeanSelector = mockk()

    @BeforeEach
    fun setUp() {
        clearMocks(signature, pjp, beanSelector)
    }

    private fun configureJoinPoint(method: java.lang.reflect.Method, target: Any) {
        every { signature.method } returns method
        every { pjp.signature } returns signature
        every { pjp.target } returns target
        every { pjp.args } returns emptyArray()
        every { pjp.proceed() } returns RESULT
    }

    private fun newAspect(
        factory: LeaderElectorFactory,
        waitTime: Duration = Duration.ZERO,
    ): LeaderElectionAspect {
        every { beanSelector.selectElectionFactory(any()) } returns
                LeaderBeanSelector.Selected("lettuceFactory", factory)
        return LeaderElectionAspect(
            beanSelector = beanSelector,
            props = LeaderAopProperties(
                defaultWaitTime = waitTime,
                defaultLeaseTime = Duration.ofSeconds(10),
                lockNamePrefix = "",
            ),
            spel = SpelExpressionEvaluator(embeddedValueResolver = { it }, allowMethodInvocation = false),
            lockNameValidator = LockNameValidator(prefix = ""),
            recorders = emptyList(),
        )
    }

    @Test
    fun `FAIL_OPEN_RUN - Real Redis 경쟁(Skipped) 시 본문 실행`() {
        // 락을 외부에서 SET NX 로 점유 — waitTime=0 인 Aspect 는 즉시 Skipped
        connection.sync().set(CONTENTION_LOCK, "holder-token", SetArgs.Builder.nx().px(10_000))

        try {
            val factory = LettuceLeaderElectorFactory(connection)
            val method = ContentionService::class.java.getDeclaredMethod("execute")
            configureJoinPoint(method, ContentionService())

            val aspect = newAspect(factory, waitTime = Duration.ZERO)
            val result = aspect.aroundLeader(pjp)

            // FAIL_OPEN_RUN: 경쟁으로 Skipped 됐지만 본문을 실행하여 결과 반환
            result shouldBeEqualTo RESULT
        } finally {
            connection.sync().del(CONTENTION_LOCK)
        }
    }

    @Test
    fun `FAIL_OPEN_RUN - Real Redis 경쟁 없을 때는 정상 Elected 경로`() {
        val factory = LettuceLeaderElectorFactory(connection)
        val method = NormalService::class.java.getDeclaredMethod("execute")
        configureJoinPoint(method, NormalService())

        val aspect = newAspect(factory, waitTime = Duration.ofSeconds(2))
        val result = aspect.aroundLeader(pjp)

        // 경쟁 없음 → Elected → 본문 실행
        result shouldBeEqualTo RESULT
    }

    @Test
    fun `FAIL_OPEN_RUN - ToxiProxy 장애 주입으로 Redis 차단 시 본문 실행`() {
        // ToxiProxy → Redis 연결을 별도 Docker 네트워크에서 구성 후 프록시 삭제로 장애 주입
        Network.newNetwork().use { network ->
            RedisServer()
                .withNetwork(network)
                .withNetworkAliases("redis")
                .use { redisContainer ->
                    ToxiproxyServer()
                        .withNetwork(network)
                        .use { toxiproxy ->
                            redisContainer.start()
                            toxiproxy.start()

                            val toxiproxyClient = ToxiproxyClient(toxiproxy.host, toxiproxy.controlPort)
                            val proxy = toxiproxyClient.createProxy(
                                "redis-proxy",
                                "0.0.0.0:8666",
                                "redis:${RedisServer.PORT}",
                            )
                            val proxyPort = toxiproxy.getMappedPort(8666)
                            val proxyRedisClient = RedisClient.create("redis://${toxiproxy.host}:$proxyPort")
                            val proxyConnection = proxyRedisClient.connect(StringCodec.UTF8)

                            try {
                                // 프록시 통해 정상 연결 확인
                                proxyConnection.sync().ping() shouldBeEqualTo "PONG"

                                // 장애 주입 — 프록시 삭제로 Redis 접근 차단
                                proxy.delete()

                                val factory = LettuceLeaderElectorFactory(proxyConnection)
                                val method = BackendErrorService::class.java.getDeclaredMethod("execute")
                                configureJoinPoint(method, BackendErrorService())

                                val aspect = newAspect(factory, waitTime = Duration.ofMillis(200))
                                // 프록시 삭제 → Redis 명령 실패 → FAIL_OPEN_RUN → 본문 실행
                                val result = aspect.aroundLeader(pjp)
                                result shouldBeEqualTo RESULT
                            } finally {
                                runCatching { proxyConnection.closeSafe() }
                                runCatching { proxyRedisClient.shutdown() }
                            }
                        }
                }
        }
    }
}
