package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockHandleElementTest {

    private fun identity() = LockIdentity(
        lockName = "element-test-lock",
        kind = LockIdentity.AnnotationKind.SINGLE,
        factoryBeanName = "testFactory",
    )

    private fun fakeDelegate(): ExtendDelegate = object : ExtendDelegate {
        private val deadline = AtomicReference(Instant.EPOCH)
        override val lastExtendDeadline: AtomicReference<Instant> get() = deadline
        override fun extend(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Extended(Instant.now())
        override fun isHeld(): Boolean = true
    }

    private fun realHandle(): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(),
            token = "tok-element",
            acquiredAtNanos = System.nanoTime(),
            extendDelegate = fakeDelegate(),
        )

    // ── Key / element ────────────────────────────────────────────────────────

    @Test
    fun `Key companion 은 LockHandleElement 와 동일 Key 객체다`() {
        val key: CoroutineContext.Key<LockHandleElement> = LockHandleElement.Key
        val element = LockHandleElement(realHandle())
        (element.key === key).shouldBeTrue()
    }

    @Test
    fun `element 를 통해 handle 에 접근 가능하다`() {
        val handle = realHandle()
        val element = LockHandleElement(handle)
        (element.handle === handle).shouldBeTrue()
    }

    @Test
    fun `data class equals 와 copy 가 handle 동일성으로 동작한다`() {
        val handle = realHandle()
        val e1 = LockHandleElement(handle)
        val e2 = LockHandleElement(handle)
        (e1 == e2).shouldBeTrue()
        e1.handle shouldBeEqualTo e2.handle
    }

    // ── CoroutineContext 통합 ────────────────────────────────────────────────

    @Test
    fun `withContext 없이 currentCoroutineContext 에 element 가 없으면 null 이다`() = runSuspendIO {
        val found = currentCoroutineContext()[LockHandleElement]
        found.shouldBeNull()
    }

    @Test
    fun `withContext 로 주입 시 currentCoroutineContext 에서 element 를 조회할 수 있다`() = runSuspendIO {
        val handle = realHandle()
        val element = LockHandleElement(handle)

        withContext(element) {
            val found = currentCoroutineContext()[LockHandleElement]
            (found === element).shouldBeTrue()
            (found!!.handle === handle).shouldBeTrue()
        }
    }

    @Test
    fun `LeaderElectionInfo 와 LockHandleElement 를 동시에 push 할 수 있다`() = runSuspendIO {
        val handle = realHandle()
        val info = LeaderElectionInfo("element-test-lock", true)
        val lockElement = LockHandleElement(handle)

        withContext(info + lockElement) {
            val foundInfo = currentCoroutineContext()[LeaderElectionInfo]
            val foundLock = currentCoroutineContext()[LockHandleElement]

            (foundInfo === info).shouldBeTrue()
            (foundLock === lockElement).shouldBeTrue()
        }
    }

    @Test
    fun `withContext 블록 탈출 후 element 는 제거된다`() = runSuspendIO {
        val handle = realHandle()
        val element = LockHandleElement(handle)

        withContext(element) {
            // inside: visible
            val found = currentCoroutineContext()[LockHandleElement]
            (found !== null).shouldBeTrue()
        }

        // outside: gone
        val afterExit = currentCoroutineContext()[LockHandleElement]
        afterExit.shouldBeNull()
    }
}
