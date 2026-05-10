package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class ApplicationExtTest: AbstractLeaderKtorTest() {

    companion object: KLoggingChannel() {
        private val SHORT_PERIOD = 50.milliseconds
        private val POLL_INTERVAL = 50.milliseconds
        private val AWAIT_TIMEOUT = 15.seconds
    }

    @Test
    fun `lockName blank 인 경우 IllegalArgumentException`() = runSuspendIO {
        val elector = RedissonSuspendLeaderElector(redissonClient)
        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }

                assertFailsWith<IllegalArgumentException> {
                    leaderScheduled(lockName = "  ", period = SHORT_PERIOD) { /* no-op */ }
                }
            }
            startApplication()
        }
    }

    @Test
    fun `period 가 0 인 경우 IllegalArgumentException`() = runSuspendIO {
        val elector = RedissonSuspendLeaderElector(redissonClient)
        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }

                assertFailsWith<IllegalArgumentException> {
                    leaderScheduled(lockName = "ok", period = Duration.ZERO) { /* no-op */ }
                }
            }
            startApplication()
        }
    }

    @Test
    fun `period 가 음수인 경우 IllegalArgumentException`() = runSuspendIO {
        val elector = RedissonSuspendLeaderElector(redissonClient)
        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }

                assertFailsWith<IllegalArgumentException> {
                    leaderScheduled(lockName = "ok", period = (-10).milliseconds) { /* no-op */ }
                }
            }
            startApplication()
        }
    }

    @Test
    fun `리더 액션이 주기적으로 실행되며 단일 인스턴스로 동작한다`() = runSuspendIO {
        val lockName = randomName()
        val elector = RedissonSuspendLeaderElector(redissonClient)
        val counter = AtomicInteger(0)

        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }

                leaderScheduled(lockName, period = SHORT_PERIOD) {
                    val current = counter.incrementAndGet()
                    log.debug { "리더 작업 실행 #$current" }
                }
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { counter.get() >= 3 }

            counter.get() shouldBeGreaterOrEqualTo 3
        }
    }

    @Test
    fun `action 예외 발생 시에도 다음 cycle 이 계속 실행된다`() = runSuspendIO {
        val lockName = randomName()
        val elector = RedissonSuspendLeaderElector(redissonClient)
        val cycles = AtomicInteger(0)
        val firstCycleConsumed = AtomicBoolean(false)

        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }

                leaderScheduled(lockName, period = SHORT_PERIOD) {
                    val current = cycles.incrementAndGet()
                    if (firstCycleConsumed.compareAndSet(false, true)) {
                        log.debug { "첫 번째 cycle 에서 의도적으로 예외 발생" }
                        error("의도된 실패 — poison-pill 방지 검증")
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

    @Test
    fun `명시 leaderElection 인자를 사용하면 plugin 미설치여도 동작한다`() = runSuspendIO {
        val lockName = randomName()
        val elector: SuspendLeaderElector = RedissonSuspendLeaderElector(redissonClient)
        val counter = AtomicInteger(0)

        testApplication {
            application {
                // 플러그인 install 생략
                leaderScheduled(lockName, SHORT_PERIOD, leaderElection = elector) {
                    counter.incrementAndGet()
                }
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { counter.get() >= 2 }

            counter.get() shouldBeGreaterOrEqualTo 2
        }
    }

    @Test
    fun `Application 종료 시 leaderScheduled job 이 자동 취소된다`() = runSuspendIO {
        val lockName = randomName()
        val elector = RedissonSuspendLeaderElector(redissonClient)
        val counter = AtomicInteger(0)

        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }
                leaderScheduled(lockName, SHORT_PERIOD) {
                    counter.incrementAndGet()
                }
            }
            startApplication()

            await.atMost(AWAIT_TIMEOUT.toJavaDuration())
                .withPollInterval(POLL_INTERVAL.toJavaDuration())
                .until { counter.get() >= 2 }
        } // testApplication 블록 종료 시 application 종료 + 스코프 취소

        val countAtStop = counter.get()
        delay(SHORT_PERIOD * 5)
        // 종료 후에는 더 이상 실행되지 않아야 한다 — 어느 정도 시간이 지나도 거의 증가하지 않음
        // 약간의 race 가 있을 수 있으므로 +2 까지 허용
        (counter.get() <= countAtStop + 2).shouldBeTrue()
    }

    @Test
    fun `leaderScheduled 는 즉시 Job 을 반환하며 수동 cancel 가능하다`() = runSuspendIO {
        val lockName = randomName()
        val elector = RedissonSuspendLeaderElector(redissonClient)
        val counter = AtomicInteger(0)

        testApplication {
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }
                val job = leaderScheduled(lockName, SHORT_PERIOD) {
                    counter.incrementAndGet()
                }

                // 잠시 실행 후 수동 취소
                withTimeoutOrNull(AWAIT_TIMEOUT) {
                    while (counter.get() < 2) delay(SHORT_PERIOD)
                }
                job.cancel()
                val countAtCancel = counter.get()
                delay(SHORT_PERIOD * 5)
                // cancel 이후 거의 증가하지 않음
                (counter.get() <= countAtCancel + 2).shouldBeTrue()
                countAtCancel shouldBeGreaterOrEqualTo 2
            }
            startApplication()
        }
    }
}
