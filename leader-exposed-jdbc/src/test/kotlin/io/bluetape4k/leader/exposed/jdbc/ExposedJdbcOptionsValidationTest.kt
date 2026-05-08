package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.leader.exposed.retry.RetryStrategy

import io.bluetape4k.leader.LeaderGroupElectionOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import io.bluetape4k.assertions.assertFailsWith

/**
 * Options data class 의 init 검증 동작 확인.
 *
 * DB 의존이 없으므로 testcontainers를 사용하지 않습니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedJdbcOptionsValidationTest {

    @Test
    fun `ExposedJdbcLeaderElectionOptions - lockOwner 256자는 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedJdbcLeaderElectionOptions(lockOwner = "x".repeat(256))
        }
    }

    @Test
    fun `ExposedJdbcLeaderElectionOptions - lockOwner 정확히 255자는 허용`() {
        ExposedJdbcLeaderElectionOptions(lockOwner = "x".repeat(255))
    }

    @Test
    fun `ExposedJdbcLeaderElectionOptions - lockOwner null은 허용`() {
        ExposedJdbcLeaderElectionOptions(lockOwner = null)
    }

    @Test
    fun `ExposedJdbcLeaderGroupElectionOptions - maxLeaders 0은 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedJdbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 0),
            )
        }
    }

    @Test
    fun `ExposedJdbcLeaderGroupElectionOptions - maxLeaders 음수는 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedJdbcLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = -1),
            )
        }
    }

    @Test
    fun `ExposedJdbcLeaderGroupElectionOptions - lockOwner 256자는 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            ExposedJdbcLeaderGroupElectionOptions(lockOwner = "y".repeat(256))
        }
    }

    @Test
    fun `RetryStrategy_Jitter - baseDelayMs 1은 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            RetryStrategy.Jitter(baseDelayMs = 1L)
        }
    }

    @Test
    fun `RetryStrategy_Exponential - baseDelayMs 0은 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            RetryStrategy.Exponential(baseDelayMs = 0L)
        }
    }

    @Test
    fun `RetryStrategy_Exponential - maxDelayMs가 baseDelayMs보다 작으면 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            RetryStrategy.Exponential(baseDelayMs = 100L, maxDelayMs = 50L)
        }
    }

    @Test
    fun `RetryStrategy_Fixed - fixedMs 0은 IllegalArgumentException 발생`() {
        assertFailsWith<IllegalArgumentException> {
            RetryStrategy.Fixed(fixedMs = 0L)
        }
    }
}
