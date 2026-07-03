package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test

class R2dbcLockCancellationTest {

    @Test
    fun `R2DBC lock operation - CancellationException 재전파`() = runSuspendIO {
        val cancellation = CancellationException("cancel r2dbc")

        val thrown = assertFailsWith<CancellationException> {
            runR2dbcLockOperationPreservingCancellation(
                onFailure = { error("CancellationException must not be handled as a DB failure") },
            ) {
                throw cancellation
            }
        }

        thrown shouldBeEqualTo cancellation
    }

    @Test
    fun `R2DBC lock operation - 일반 예외는 fallback 으로 처리`() = runSuspendIO {
        val failure = IllegalStateException("db failed")
        var handled = false

        val result = runR2dbcLockOperationPreservingCancellation(
            onFailure = { e ->
                handled = true
                e shouldBeEqualTo failure
                false
            },
        ) {
            throw failure
        }

        result.shouldBeFalse()
        handled.shouldBeTrue()
    }
}
