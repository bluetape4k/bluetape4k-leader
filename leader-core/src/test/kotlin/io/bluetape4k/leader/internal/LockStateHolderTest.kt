package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockStateHolderTest {

    private fun identity(name: String = "test-lock") = LockIdentity(
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

    private fun realHandle(name: String = "test-lock", depth: Int = 0): LeaderLockHandle.Real =
        LeaderLockHandle.real(
            identity = identity(name),
            token = "tok-$name",
            acquiredAtNanos = System.nanoTime(),
            reentryDepth = depth,
            extendDelegate = fakeDelegate(),
        )

    @BeforeEach
    fun cleanup() {
        // drain any leftover state from previous tests on the same thread
        while (LockStateHolder.pop() != null) { /* drain */ }
        LockStateHolder.cleanup()
    }

    @Test
    fun `peekSync returns null when stack is empty`() {
        LockStateHolder.peekSync().shouldBeNull()
    }

    @Test
    fun `push then peekSync returns pushed handle`() {
        val handle = realHandle()
        LockStateHolder.push(handle)
        try {
            LockStateHolder.peekSync() shouldBeEqualTo handle
        } finally {
            LockStateHolder.pop()
            LockStateHolder.cleanup()
        }
    }

    @Test
    fun `pop removes top element — stack LIFO order`() {
        val h1 = realHandle("lock-1")
        val h2 = realHandle("lock-2")
        LockStateHolder.push(h1)
        LockStateHolder.push(h2)

        LockStateHolder.pop() shouldBeEqualTo h2
        LockStateHolder.pop() shouldBeEqualTo h1
        LockStateHolder.pop().shouldBeNull()
    }

    @Test
    fun `withPushed restores stack after block`() {
        val handle = realHandle()
        var seenInsideBlock: LeaderLockHandle? = null

        LockStateHolder.withPushed(handle) {
            seenInsideBlock = LockStateHolder.peekSync()
        }

        seenInsideBlock shouldBeEqualTo handle
        LockStateHolder.peekSync().shouldBeNull()
    }

    @Test
    fun `withPushed restores stack even when block throws`() {
        val handle = realHandle()

        runCatching {
            LockStateHolder.withPushed(handle) {
                error("simulated failure")
            }
        }

        LockStateHolder.peekSync().shouldBeNull()
    }

    @Test
    fun `peekSyncMatching returns handle matching lockName`() {
        val h1 = realHandle("lock-A")
        val h2 = realHandle("lock-B")
        LockStateHolder.push(h1)
        LockStateHolder.push(h2)

        try {
            LockStateHolder.peekSyncMatching("lock-A") shouldBeEqualTo h1
            LockStateHolder.peekSyncMatching("lock-B") shouldBeEqualTo h2
            LockStateHolder.peekSyncMatching("lock-C").shouldBeNull()
        } finally {
            LockStateHolder.pop()
            LockStateHolder.pop()
            LockStateHolder.cleanup()
        }
    }
}
