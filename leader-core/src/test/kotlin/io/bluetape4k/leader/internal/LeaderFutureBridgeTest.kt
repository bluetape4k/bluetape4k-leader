package io.bluetape4k.leader.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean

class LeaderFutureBridgeTest {

    @Test
    fun `map은 성공 값을 변환한다`() {
        val mapped = LeaderFutureBridge.map(CompletableFuture.completedFuture("done")) { value, failure ->
            failure shouldBeEqualTo null
            requireNotNull(value).length
        }

        mapped.join() shouldBeEqualTo 4
    }

    @Test
    fun `map은 source 실패를 mapper에 전달한다`() {
        val failure = IllegalStateException("source failed")
        val mapped = LeaderFutureBridge.map(CompletableFuture.failedFuture<String>(failure)) { _, observed ->
            val observedFailure = requireNotNull(observed)
            observedFailure shouldBeEqualTo failure
            throw observedFailure
        }

        assertFailsWith<CompletionException> { mapped.join() }.cause shouldBeEqualTo failure
    }

    @Test
    fun `map은 source cancellation을 보존한다`() {
        val source = CompletableFuture<String>()
        val mapped = LeaderFutureBridge.map(source) { _, failure ->
            throw requireNotNull(failure)
        }

        source.cancel(false).shouldBeTrue()

        assertFailsWith<CompletionException> { mapped.join() }.cause shouldBeInstanceOf CancellationException::class
    }

    @Test
    fun `map 완료 후 cancel은 source 상태를 바꾸지 않는다`() {
        val source = CompletableFuture.completedFuture("done")
        val mapped = LeaderFutureBridge.map(source) { value, _ -> value }

        mapped.join() shouldBeEqualTo "done"
        mapped.cancel(false).shouldBeFalse()
        source.isCancelled.shouldBeFalse()
    }

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
    fun `flatMap 반환 future 취소를 원본 future 로 전파한다`() {
        val source = CompletableFuture<String>()

        val bridged = LeaderFutureBridge.flatMap(source) { value, _ ->
            CompletableFuture.completedFuture(value)
        }

        bridged.cancel(false).shouldBeTrue()
        source.isCancelled.shouldBeTrue()
    }

    @Test
    fun `flatMap은 cleanup stage 완료 후 terminal state를 반환한다`() {
        val source = CompletableFuture.failedFuture<String>(IllegalStateException("failed"))
        val cleanup = CompletableFuture<String>()

        val bridged = LeaderFutureBridge.flatMap(source) { _, _ -> cleanup }

        bridged.isDone.shouldBeFalse()
        cleanup.complete("cleaned")
        bridged.join() shouldBeEqualTo "cleaned"
    }

    @Test
    fun `action 실행 전 취소는 action을 시작하지 않는다`() {
        val source = CompletableFuture<String>()
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val bridged = LeaderFutureBridge.map(source, cancellationRelay) { value, _ -> value }
        val invoked = AtomicBoolean()

        bridged.cancel(false).shouldBeTrue()
        val actionFuture = cancellationRelay.invoke {
            invoked.set(true)
            CompletableFuture.completedFuture("started")
        }

        invoked.get().shouldBeFalse()
        actionFuture.isCompletedExceptionally.shouldBeTrue()
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
