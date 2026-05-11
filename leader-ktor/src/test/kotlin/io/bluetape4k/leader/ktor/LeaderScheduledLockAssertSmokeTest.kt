package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Job
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * AC-22b — `leader-ktor` plugin propagation smoke test (Issue #79, T13 PR 9).
 *
 * ## 검증 범위
 * `leaderScheduled { ... }` background action 안에서 [LockAssert.assertLockedSuspend] /
 * [LockExtender.extendActiveLockDetailedSuspend] 가 [SuspendLeaderElector] 의 capture 메커니즘을
 * 통해 정상 propagation 되는지 검증합니다. plugin 자체는 `Application.attributes` 만 사용하며
 * `LockHandleElement` 전파는 elector 의 capture (Mongo / Redisson / R2DBC / Hazelcast / ZooKeeper /
 * Local Suspend) 가 담당합니다.
 *
 * ## 미지원 시나리오
 * - `Application.routing` / `PipelineContext` / 임의 Reactor operator 등 비-`leaderScheduled` 표면에서는
 *   `LockHandleElement` 전파가 불가능합니다. README "미지원 시나리오" 노트와 동일 정책 (Mono 분기와 동일).
 */
class LeaderScheduledLockAssertSmokeTest: AbstractLeaderKtorTest() {

    companion object: KLoggingChannel() {
        private val SHORT_PERIOD = 50.milliseconds
        private val POLL_INTERVAL = 50.milliseconds
        private val AWAIT_TIMEOUT = 15.seconds
    }

    @Test
    fun `leaderScheduled body 안에서 LockAssert assertLockedSuspend 통과`() = runSuspendIO {
        val elector = RedissonSuspendLeaderElector(redissonClient)
        val lockName = randomName()
        val asserted = AtomicReference<Throwable?>(null)
        val passes = AtomicReference(0)

        testApplication {
            var job: Job? = null
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }
                job = leaderScheduled(lockName, SHORT_PERIOD) {
                    try {
                        LockAssert.assertLockedSuspend()
                        LockAssert.assertLockedSuspend(lockName)
                        passes.set(passes.get() + 1)
                    } catch (t: Throwable) {
                        asserted.set(t)
                    }
                }
            }
            startApplication()
            await atMost AWAIT_TIMEOUT.toJavaDuration() withPollInterval POLL_INTERVAL.toJavaDuration() until {
                passes.get() >= 1 || asserted.get() != null
            }
            job?.cancel()
        }

        // body 내부에서 던진 예외가 있으면 실패
        (asserted.get() == null).shouldBeTrue()
        (passes.get() >= 1).shouldBeTrue()
    }

    @Test
    fun `leaderScheduled body 안에서 LockExtender extendActiveLockDetailedSuspend returns Extended`() = runSuspendIO {
        val elector = RedissonSuspendLeaderElector(redissonClient)
        val lockName = randomName()
        val captured = AtomicReference<ExtendOutcome?>(null)

        testApplication {
            var job: Job? = null
            application {
                install(LeaderElectionPlugin) { leaderElection = elector }
                job = leaderScheduled(lockName, SHORT_PERIOD) {
                    if (captured.get() == null) {
                        captured.set(LockExtender.extendActiveLockDetailedSuspend(30.seconds))
                    }
                }
            }
            startApplication()
            await atMost AWAIT_TIMEOUT.toJavaDuration() withPollInterval POLL_INTERVAL.toJavaDuration() until {
                captured.get() != null
            }
            job?.cancel()
        }

        captured.get().shouldBeInstanceOf<ExtendOutcome.Extended>()
    }
}
