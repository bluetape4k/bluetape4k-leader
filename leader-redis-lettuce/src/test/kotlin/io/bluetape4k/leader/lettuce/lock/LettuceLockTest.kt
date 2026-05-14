package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LettuceLockTest: AbstractLettuceLeaderTest() {

    private val keys = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        if (keys.isNotEmpty()) {
            connection.sync().del(*keys.toTypedArray())
            keys.clear()
        }
    }

    private fun lockKey(): String =
        randomName().also { keys += it }

    @Test
    fun `tryLock unlock extend 는 sync lock 상태를 갱신한다`() {
        val lock = LettuceLock(connection, lockKey(), defaultLeaseTime = 2.seconds)

        lock.isLocked().shouldBeFalse()
        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.currentToken().shouldBeNull()
        lock.extend().shouldBeFalse()
        lock.extendDetailed().shouldBeInstanceOf<ExtendOutcome.NotHeld>()

        lock.tryLock(waitTime = 100.milliseconds, leaseTime = 2.seconds).shouldBeTrue()
        lock.isLocked().shouldBeTrue()
        lock.isHeldByCurrentInstance().shouldBeTrue()
        lock.currentToken().shouldNotBeNull()

        lock.extend(2.seconds).shouldBeTrue()
        lock.extendDetailed(2.seconds).shouldBeInstanceOf<ExtendOutcome.Extended>()

        lock.unlock()
        lock.isLocked().shouldBeFalse()
        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.currentToken().shouldBeNull()
    }

    @Test
    fun `lock 은 timeout 되면 예외를 던지고 unlock 은 미보유 상태를 거부한다`() {
        val key = lockKey()
        val owner = LettuceLock(connection, key, defaultLeaseTime = 2.seconds)
        val contender = LettuceLock(connection, key, defaultLeaseTime = 2.seconds)

        owner.tryLock(leaseTime = 2.seconds).shouldBeTrue()

        contender.tryLock(waitTime = 100.milliseconds, leaseTime = 2.seconds).shouldBeFalse()
        assertFailsWith<IllegalStateException> {
            contender.lock(leaseTime = 2.seconds, maxWaitTime = 100.milliseconds)
        }
        assertFailsWith<IllegalStateException> {
            contender.unlock()
        }

        owner.unlock()
    }

    @Test
    fun `async lock API 는 획득 extend 해제를 처리한다`() {
        val key = lockKey()
        val lock = LettuceLock(connection, key, defaultLeaseTime = 2.seconds)
        val contender = LettuceLock(connection, key, defaultLeaseTime = 2.seconds)

        lock.tryLockAsync(waitTime = 100.milliseconds, leaseTime = 2.seconds).get().shouldBeTrue()
        contender.tryLockAsync(waitTime = 100.milliseconds, leaseTime = 2.seconds).get().shouldBeFalse()
        lock.extendAsync(2.seconds).get().shouldBeTrue()

        lock.unlockAsync().get()
        lock.isLocked().shouldBeFalse()
        lock.extendAsync(2.seconds).get().shouldBeFalse()

        assertFailsWith<ExecutionException> {
            lock.unlockAsync().get()
        }
    }

    @Test
    fun `suspend lock API 는 sync lock 과 같은 상태 계약을 따른다`() = runSuspendIO {
        val key = lockKey()
        val lock = LettuceSuspendLock(connection, key, defaultLeaseTime = 2.seconds)
        val contender = LettuceSuspendLock(connection, key, defaultLeaseTime = 2.seconds)

        lock.isLocked().shouldBeFalse()
        lock.isHeldByCurrentInstance().shouldBeFalse()
        lock.currentToken().shouldBeNull()
        lock.extend().shouldBeFalse()
        lock.extendDetailed().shouldBeInstanceOf<ExtendOutcome.NotHeld>()

        lock.tryLock(waitTime = 100.milliseconds, leaseTime = 2.seconds).shouldBeTrue()
        lock.isLocked().shouldBeTrue()
        lock.isHeldByCurrentInstance().shouldBeTrue()
        lock.currentToken().shouldNotBeNull()

        contender.tryLock(waitTime = 100.milliseconds, leaseTime = 2.seconds).shouldBeFalse()
        assertFailsWith<IllegalStateException> {
            contender.lock(leaseTime = 2.seconds, maxWaitTime = 100.milliseconds)
        }

        lock.extend(2.seconds).shouldBeTrue()
        lock.extendDetailed(2.seconds).shouldBeInstanceOf<ExtendOutcome.Extended>()
        lock.unlock()
        lock.isLocked().shouldBeFalse()

        assertFailsWith<IllegalStateException> {
            lock.unlock()
        }
    }
}
