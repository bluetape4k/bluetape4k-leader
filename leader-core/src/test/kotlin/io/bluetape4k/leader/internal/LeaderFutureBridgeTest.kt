package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class LeaderFutureBridgeTest {

    @Test
    fun `observe 반환 future 취소를 원본 future 로 전파한다`() {
        val source = CompletableFuture<String>()

        val observed = LeaderFutureBridge.observe(source) { _, _ -> }

        observed.cancel(false).shouldBeTrue()
        source.isCancelled.shouldBeTrue()
    }

    @Test
    fun `observe callback 실패를 반환 future 로 전파한다`() {
        val failure = IllegalStateException("observer failed")

        val observed = LeaderFutureBridge.observe(CompletableFuture.completedFuture("done")) { _, _ ->
            throw failure
        }

        assertFailsWith<CompletionException> { observed.join() }.cause shouldBeEqualTo failure
    }

    @Test
    fun `VirtualFuture cancellation bridge 는 반환 future 취소를 원본으로 전파한다`() {
        val source = CompletableFuture<String>()
        val virtual = VirtualFuture(source as java.util.concurrent.Future<String>)

        val bridged = LeaderFutureBridge.propagateCancellation(virtual)

        bridged.cancel(false).shouldBeTrue()
        source.isCancelled.shouldBeTrue()
    }
}
