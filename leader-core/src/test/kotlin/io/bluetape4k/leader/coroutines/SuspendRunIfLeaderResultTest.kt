package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuspendRunIfLeaderResultTest {

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    // ── SuspendLeaderElector ─────────────────────────────────────────────────

    @Test
    fun `SuspendLeaderElector - 리더 선출 성공 시 Elected 를 반환한다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()
        val result = election.runIfLeaderResultSuspend(randomLockName()) { "ok" }

        (result is LeaderRunResult.Elected).shouldBeTrue()
        (result as LeaderRunResult.Elected<String>).value shouldBeEqualTo "ok"
    }

    @Test
    fun `SuspendLeaderElector - action 이 null 을 반환해도 Elected 로 분류된다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()
        val result = election.runIfLeaderResultSuspend(randomLockName()) { null }

        (result is LeaderRunResult.Elected).shouldBeTrue()
        (result as LeaderRunResult.Elected<Nothing?>).value.shouldBeNull()
    }

    @Test
    fun `SuspendLeaderElector - action 실패는 ActionFailed 로 분류된다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()
        val failure = IllegalStateException("suspend-boom")
        val result = election.runIfLeaderResultSuspend<Any?>(randomLockName()) { throw failure }

        (result is LeaderRunResult.ActionFailed).shouldBeTrue()
        (result as LeaderRunResult.ActionFailed).cause shouldBeInstanceOf IllegalStateException::class
        result.cause.message shouldBeEqualTo failure.message
    }

    @Test
    fun `SuspendLeaderElector - CancellationException 은 ActionFailed 로 감싸지 않고 재전파한다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()

        assertFailsWith<CancellationException> {
            election.runIfLeaderResultSuspend<Any?>(randomLockName()) {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `SuspendLeaderElector - 락 획득 실패 시 Skipped 를 반환한다`() = runSuspendIO {
        val skipElection = LocalSuspendLeaderElector(
            LeaderElectionOptions(waitTime = 50.milliseconds)
        )
        val lockName = randomLockName()
        val holderReady = Channel<Unit>(1)

        val holder = async {
            skipElection.runIfLeader(lockName) {
                holderReady.send(Unit)
                delay(500.milliseconds)
                "holder"
            }
        }

        holderReady.receive()

        val result = skipElection.runIfLeaderResultSuspend(lockName) { "should-skip" }
        result shouldBeEqualTo LeaderRunResult.Skipped

        holder.await()
    }

    @Test
    fun `SuspendLeaderElector - Elected value 로 직접 runIfLeader 결과값을 얻을 수 있다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()
        val result = election.runIfLeaderResultSuspend(randomLockName()) { 42 }

        (result is LeaderRunResult.Elected).shouldBeTrue()
        (result as LeaderRunResult.Elected).value shouldBeEqualTo 42
    }

    @Test
    fun `SuspendLeaderElector - 락 해제 후 재시도 시 Elected 를 반환한다`() = runSuspendIO {
        val election = LocalSuspendLeaderElector()
        val lockName = randomLockName()

        val first = election.runIfLeaderResultSuspend(lockName) { "first" }
        (first is LeaderRunResult.Elected).shouldBeTrue()

        val second = election.runIfLeaderResultSuspend(lockName) { "second" }
        (second is LeaderRunResult.Elected).shouldBeTrue()
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "second"
    }

    // ── SuspendLeaderGroupElector ────────────────────────────────────────────

    @Test
    fun `SuspendLeaderGroupElector - 슬롯 획득 성공 시 Elected 를 반환한다`() = runSuspendIO {
        val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 2))
        val result = election.runIfLeaderResultSuspend(randomLockName()) { "group-ok" }

        (result is LeaderRunResult.Elected).shouldBeTrue()
        (result as LeaderRunResult.Elected<String>).value shouldBeEqualTo "group-ok"
    }

    @Test
    fun `SuspendLeaderGroupElector - action 이 null 을 반환해도 Elected 로 분류된다`() = runSuspendIO {
        val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 1))
        val result = election.runIfLeaderResultSuspend(randomLockName()) { null }

        (result is LeaderRunResult.Elected).shouldBeTrue()
        (result as LeaderRunResult.Elected<Nothing?>).value.shouldBeNull()
    }

    @Test
    fun `SuspendLeaderGroupElector - action 실패는 ActionFailed 로 분류된다`() = runSuspendIO {
        val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 1))
        val failure = IllegalArgumentException("group-suspend-boom")
        val result = election.runIfLeaderResultSuspend<Any?>(randomLockName()) { throw failure }

        (result is LeaderRunResult.ActionFailed).shouldBeTrue()
        (result as LeaderRunResult.ActionFailed).cause shouldBeInstanceOf IllegalArgumentException::class
        result.cause.message shouldBeEqualTo failure.message
    }

    @Test
    fun `SuspendLeaderGroupElector - CancellationException 은 ActionFailed 로 감싸지 않고 재전파한다`() =
        runSuspendIO {
            val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 1))

            assertFailsWith<CancellationException> {
                election.runIfLeaderResultSuspend<Any?>(randomLockName()) {
                    throw CancellationException("group-cancelled")
                }
            }
        }

    @Test
    fun `SuspendLeaderGroupElector - 슬롯 가득 찰 때 Skipped 를 반환한다`() = runSuspendIO {
        val election = LocalSuspendLeaderGroupElector(
            LeaderGroupElectionOptions(maxLeaders = 1, waitTime = 50.milliseconds)
        )
        val lockName = randomLockName()
        val holderReady = Channel<Unit>(1)

        val holder = async {
            election.runIfLeader(lockName) {
                holderReady.send(Unit)
                delay(500.milliseconds)
                "holder"
            }
        }

        holderReady.receive()

        val result = election.runIfLeaderResultSuspend(lockName) { "should-skip" }
        result shouldBeEqualTo LeaderRunResult.Skipped

        holder.await()
    }
}
