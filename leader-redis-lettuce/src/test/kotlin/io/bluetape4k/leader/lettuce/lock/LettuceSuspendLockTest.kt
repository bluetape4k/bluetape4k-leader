package io.bluetape4k.leader.lettuce.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LettuceSuspendLockTest: AbstractLettuceLeaderTest() {

    companion object: KLoggingChannel()

    private val keys = ConcurrentLinkedQueue<String>()

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
