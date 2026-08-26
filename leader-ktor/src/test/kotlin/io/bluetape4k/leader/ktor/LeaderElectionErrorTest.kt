package io.bluetape4k.leader.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Test

class LeaderElectionErrorTest {

    @Test
    fun `기본 payload는 allow-list 필드와 stable status만 포함한다`() {
        val context = LeaderElectionErrorContext(
            code = LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
            message = "leader state is temporarily unavailable",
            status = HttpStatusCode.ServiceUnavailable,
            lockName = "internal-job",
        )

        context.toJson(exposeLockName = false) shouldBeEqualTo
            """{"code":"BACKEND_UNAVAILABLE","message":"leader state is temporarily unavailable","status":503}"""
    }

    @Test
    fun `typed override는 허용된 status와 lockName만 바꿀 수 있다`() {
        val override = LeaderElectionErrorOverride(
            status = HttpStatusCode.Locked,
            exposeLockName = true,
        )
        val context = contextFor(LeaderElectionErrorCode.LEADER_LOCKED)

        context.withOverride(override).toJson(exposeLockName = true) shouldContain "\"lockName\""
        context.withOverride(override).status shouldBeEqualTo HttpStatusCode.Locked
    }

    @Test
    fun `public context와 override는 오류 status allow-list를 강제한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionErrorContext(
                code = LeaderElectionErrorCode.INTERNAL,
                message = "hidden",
                status = HttpStatusCode.OK,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderElectionErrorOverride(status = HttpStatusCode.TooManyRequests)
        }
    }

    @Test
    fun `status matrix와 json escaping은 순서와 redaction을 보존한다`() {
        val expected = listOf(
            LeaderElectionErrorCode.INVALID_LOCK_NAME to HttpStatusCode.BadRequest,
            LeaderElectionErrorCode.NOT_LEADER to HttpStatusCode.ServiceUnavailable,
            LeaderElectionErrorCode.LEADER_LOCKED to HttpStatusCode.Locked,
            LeaderElectionErrorCode.BACKEND_UNAVAILABLE to HttpStatusCode.ServiceUnavailable,
            LeaderElectionErrorCode.CONFIGURATION to HttpStatusCode.InternalServerError,
            LeaderElectionErrorCode.INTERNAL to HttpStatusCode.InternalServerError,
            LeaderElectionErrorCode.INVALID_CURSOR to HttpStatusCode.BadRequest,
        )

        expected.forEach { (code, status) ->
            val context = LeaderElectionErrorContext(
                code = code,
                message = "message with \"quotes\" and \\slash",
                status = status,
                lockName = "secret-lock",
            )
            context.status shouldBeEqualTo status
            context.toJson().contains("secret-lock").shouldBeEqualTo(false)
            context.toJson(exposeLockName = true) shouldContain "\\\"quotes\\\""
        }
    }

    @Test
    fun `backend cause는 public context와 payload에 보존되지 않는다`() = runSuspendIO {
        val context = toErrorContext(
            code = LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
            lockName = "secret-lock",
            cause = IllegalStateException("backend-secret"),
        )

        context.toJson(exposeLockName = false).contains("backend-secret").shouldBeEqualTo(false)
        context.toJson(exposeLockName = false).contains("IllegalStateException").shouldBeEqualTo(false)
        context.toJson(exposeLockName = true) shouldContain "\"lockName\":\"secret-lock\""
    }

    private fun contextFor(code: LeaderElectionErrorCode): LeaderElectionErrorContext =
        LeaderElectionErrorContext(
            code = code,
            message = "leader election error",
            status = when (code) {
                LeaderElectionErrorCode.INVALID_LOCK_NAME,
                LeaderElectionErrorCode.INVALID_CURSOR,
                -> HttpStatusCode.BadRequest

                LeaderElectionErrorCode.LEADER_LOCKED -> HttpStatusCode.Locked
                LeaderElectionErrorCode.NOT_LEADER,
                LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
                -> HttpStatusCode.ServiceUnavailable

                LeaderElectionErrorCode.CONFIGURATION,
                LeaderElectionErrorCode.INTERNAL,
                -> HttpStatusCode.InternalServerError
            },
            lockName = "job",
        )
}
