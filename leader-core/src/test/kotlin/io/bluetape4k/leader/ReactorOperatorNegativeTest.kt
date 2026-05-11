package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Step 3-P R5 mitigation 부정 테스트 — Reactor non-suspend operator 미지원 검증.
 *
 * Reactor 의 `.map {}` / `.filter {}` 등 non-suspend operator 안에서는
 * ThreadLocal 도 `CoroutineContext` 도 없으므로 [LockAssert.assertLocked] 와
 * [LockExtender.extendActiveLock] 은 반드시 throw / false 를 반환해야 한다.
 *
 * **권장 패턴**: `.flatMap { mono { ... } }` 내부에서 `withContext(LockHandleElement(...))` 를 사용.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReactorOperatorNegativeTest {

    private fun identity(name: String = "reactor-lock") = LockIdentity(
        lockName = name,
        kind = LockIdentity.AnnotationKind.SINGLE,
        factoryBeanName = "testFactory",
    )

    private fun fakeDelegate(): ExtendDelegate = object : ExtendDelegate {
        private val deadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = deadline
        override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Extended(Instant.now())
        override fun isHeld(): Boolean = true
    }

    private fun realHandle(name: String = "reactor-lock"): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(name),
            token = "tok-reactor",
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = fakeDelegate(),
        )

    /**
     * R5 — `Mono.map {}` (non-suspend) 안에서 [LockAssert.assertLocked] 호출 시 throw.
     *
     * ThreadLocal 에 Real handle 이 push 되어 있더라도 Reactor operator 는 다른 스레드에서
     * 실행될 수 있으므로 ThreadLocal 이 전파되지 않을 수 있다.
     * 이 테스트는 ThreadLocal 이 전파되지 않는 케이스를 검증한다.
     * (Reactor 가 동일 스레드에서 실행될 때는 ThreadLocal 이 보일 수 있으므로,
     * `LockStateHolder` 에 아무것도 push 하지 않은 상태에서 검증)
     */
    @Test
    fun `LockAssert assertLocked inside Mono map throws when no active scope (R5)`() {
        // No ThreadLocal scope — simulates a Reactor operator on a different thread
        val mono = Mono.just("value").map {
            LockAssert.assertLocked()
            it
        }

        assertFailsWith<IllegalStateException> {
            mono.block()
        }
    }

    /**
     * R5 — `Mono.fromCallable {}` 안에서도 ThreadLocal 없으면 throw.
     */
    @Test
    fun `LockAssert assertLocked inside Mono fromCallable throws when no active scope (R5)`() {
        val mono = Mono.fromCallable {
            LockAssert.assertLocked()
            "result"
        }

        assertFailsWith<IllegalStateException> {
            mono.block()
        }
    }

    /**
     * R5 positive path — `.flatMap { mono { withContext(LockHandleElement) { ... } } }` 패턴은 통과.
     *
     * suspend `mono {}` builder 안에서 `withContext(LockHandleElement(handle))` 를 사용하면
     * [LockAssert.assertLockedSuspend] 가 정상 통과함을 검증.
     */
    @Test
    fun `LockAssert assertLockedSuspend inside mono flatMap with LockHandleElement passes (R5)`() {
        val handle = realHandle()

        val result = Mono.just("value")
            .flatMap { value ->
                mono {
                    withContext(LockHandleElement(handle)) {
                        LockAssert.assertLockedSuspend()  // must not throw
                        value
                    }
                }
            }
            .block()

        assert(result == "value") { "Expected 'value', got $result" }
    }

    /**
     * R5 — [LockExtender.extendActiveLock] 도 Mono map 안에서 false 반환.
     *
     * ThreadLocal 없는 환경에서 extendActiveLock 은 outsideScope → false + WARN.
     */
    @Test
    fun `LockExtender extendActiveLock inside Mono map returns false when no active scope (R5)`() {
        var result: Boolean? = null

        Mono.just("value")
            .map {
                result = LockExtender.extendActiveLock(30.seconds)
                it
            }
            .block()

        assert(result == false) { "Expected false, got $result" }
    }

    /**
     * R5 positive path — suspend `mono {}` + [LockExtender.extendActiveLockSuspend] 는 정상 동작.
     */
    @Test
    fun `LockExtender extendActiveLockSuspend inside mono flatMap with LockHandleElement returns true (R5)`() {
        val handle = realHandle()
        var result: Boolean? = null

        Mono.just("value")
            .flatMap { _ ->
                mono {
                    withContext(LockHandleElement(handle)) {
                        result = LockExtender.extendActiveLockSuspend(30.seconds)
                    }
                    "done"
                }
            }
            .block()

        assert(result == true) { "Expected true, got $result" }
    }

    /**
     * ThreadLocal scope 안에서 Reactor Mono.map 을 실행할 때의 동작 검증.
     *
     * Reactor 가 호출 스레드와 동일한 스레드에서 동기 실행되면 ThreadLocal 이 보일 수 있다.
     * 이 경우 assertLocked() 는 통과한다 — 이는 동일 스레드에서의 동기 실행이기 때문.
     * 이 테스트는 해당 동작을 문서화한다 (보장 아님, 구현 세부).
     *
     * 핵심: **비동기 스케줄러** (e.g., `publishOn(Schedulers.boundedElastic())`) 를 사용하면
     * 다른 스레드에서 실행되어 ThreadLocal 이 전파되지 않으므로 반드시 coroutineContext 방식 사용.
     */
    @Test
    fun `LockStateHolder withPushed and synchronous Mono on same thread sees ThreadLocal`() {
        var sawLocked: Boolean? = null

        LockStateHolder.withPushed(realHandle()) {
            // Synchronous Mono on caller thread — ThreadLocal is visible
            Mono.fromCallable {
                sawLocked = LockAssert.isLocked()
            }.block()
        }

        // On same thread, ThreadLocal is visible
        assert(sawLocked == true) { "Expected true (same-thread synchronous Mono), got $sawLocked" }
    }
}
