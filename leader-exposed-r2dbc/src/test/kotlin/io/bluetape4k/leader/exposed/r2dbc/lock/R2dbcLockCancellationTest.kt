package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class R2dbcLockCancellationTest {

    companion object: KLoggingChannel()

    @Test
    fun `legacy cancellation helper remains in its original JVM facade`() {
        val method = Class.forName(
            "io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLockKt"
        ).getDeclaredMethod(
            "runR2dbcLockOperationPreservingCancellation",
            Function1::class.java,
            Function1::class.java,
            kotlin.coroutines.Continuation::class.java,
        )

        method.returnType shouldBeEqualTo Any::class.java
        (Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers)) shouldBeEqualTo true
    }

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
