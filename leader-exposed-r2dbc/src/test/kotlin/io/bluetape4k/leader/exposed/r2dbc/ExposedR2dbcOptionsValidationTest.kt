package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.ExposedLeaderConstants
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ExposedR2dbcOptionsValidationTest {

    companion object: KLoggingChannel()

    // ─── ExposedR2dbcLeaderElectionOptions ───────────────────────────────────

    @Test
    fun `Default 인스턴스는 기본값을 가진다`() {
        val opts = ExposedR2dbcLeaderElectionOptions.Default

        log.debug { "opts=$opts" }
        opts.leaderOptions shouldBeEqualTo LeaderElectionOptions.Default
        opts.retryStrategy.shouldBeInstanceOf<RetryStrategy.Jitter>()
        opts.recordHistory.shouldBeFalse()
        opts.lockOwner.shouldBeNull()
    }

    @Test
    fun `lockOwner 최대 길이 이내는 허용된다`() {
        val maxOwner = "a".repeat(ExposedLeaderConstants.LOCK_OWNER_LENGTH)
        val opts = ExposedR2dbcLeaderElectionOptions(lockOwner = maxOwner)

        log.debug { "opts=$opts" }
        opts.lockOwner shouldBeEqualTo maxOwner
    }

    @Test
    fun `lockOwner 최대 길이 초과 시 IllegalArgumentException이 발생한다`() {
        val tooLong = "a".repeat(ExposedLeaderConstants.LOCK_OWNER_LENGTH + 1)

        assertFailsWith<IllegalArgumentException> {
            ExposedR2dbcLeaderElectionOptions(lockOwner = tooLong)
        }
    }

    @Test
    fun `recordHistory = true 옵션이 설정된다`() {
        val opts = ExposedR2dbcLeaderElectionOptions(recordHistory = true)

        log.debug { "opts=$opts" }
        opts.recordHistory.shouldBeTrue()
    }

    @Test
    fun `커스텀 leaderOptions가 적용된다`() {
        val custom = LeaderElectionOptions(
            waitTime = 3.seconds,
            leaseTime = 60.seconds,
        )
        val opts = ExposedR2dbcLeaderElectionOptions(leaderOptions = custom)

        log.debug { "opts=$opts" }
        opts.leaderOptions shouldBeEqualTo custom
    }

    @Test
    fun `Fixed retryStrategy가 적용된다`() {
        val opts = ExposedR2dbcLeaderElectionOptions(retryStrategy = RetryStrategy.Fixed(fixedMs = 100L))
        opts.retryStrategy.shouldBeInstanceOf<RetryStrategy.Fixed>()
    }

    // ─── ExposedR2dbcLeaderGroupElectionOptions ───────────────────────────────

    @Test
    fun `그룹 Default 인스턴스는 기본값을 가진다`() {
        val opts = ExposedR2dbcLeaderGroupElectionOptions.Default

        log.debug { "opts=$opts" }
        opts.leaderGroupOptions shouldBeEqualTo LeaderGroupElectionOptions.Default
        opts.retryStrategy.shouldBeInstanceOf<RetryStrategy.Jitter>()
        opts.recordHistory.shouldBeFalse()
        opts.lockOwner.shouldBeNull()
    }

    @Test
    fun `그룹 maxLeaders가 leaderGroupOptions에서 올바르게 위임된다`() {
        val opts = ExposedR2dbcLeaderGroupElectionOptions(
            leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 5)
        )

        log.debug { "opts=$opts" }
        opts.maxLeaders shouldBeEqualTo 5
    }

    @Test
    fun `그룹 maxLeaders = 0 이면 IllegalArgumentException이 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedR2dbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 0)
            )
        }
    }

    @Test
    fun `그룹 maxLeaders 음수이면 IllegalArgumentException이 발생한다`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedR2dbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = -1)
            )
        }
    }

    @Test
    fun `그룹 lockOwner 최대 길이 초과 시 IllegalArgumentException이 발생한다`() {
        val tooLong = "b".repeat(ExposedLeaderConstants.LOCK_OWNER_LENGTH + 1)
        assertFailsWith<IllegalArgumentException> {
            ExposedR2dbcLeaderGroupElectionOptions(lockOwner = tooLong)
        }
    }

    @Test
    fun `그룹 recordHistory = true 옵션이 설정된다`() {
        val opts = ExposedR2dbcLeaderGroupElectionOptions(recordHistory = true)

        log.debug { "opts=$opts" }
        opts.recordHistory.shouldBeTrue()
    }

    @Test
    fun `그룹 커스텀 leaderGroupOptions가 적용된다`() {
        val custom = LeaderGroupElectionOptions(
            maxLeaders = 4,
            waitTime = 5.seconds,
            leaseTime = 30.seconds,
        )
        val opts = ExposedR2dbcLeaderGroupElectionOptions(leaderGroupOptions = custom)

        log.debug { "opts=$opts" }
        opts.leaderGroupOptions shouldBeEqualTo custom
        opts.maxLeaders shouldBeEqualTo 4
    }
}
