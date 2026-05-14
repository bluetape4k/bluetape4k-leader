@file:Suppress("DEPRECATION")

package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds

class LettuceSemaphoreTest: AbstractLettuceLeaderTest() {

    private val keys = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        if (keys.isNotEmpty()) {
            connection.sync().del(*keys.toTypedArray())
            keys.clear()
        }
    }

    private fun semaphoreKey(): String =
        randomName().also { keys += it }

    @Test
    fun `sync semaphore 는 permits 획득 해제 timeout 을 처리한다`() {
        val semaphore = LettuceSemaphore(connection, semaphoreKey(), totalPermits = 2)

        semaphore.availablePermits() shouldBeEqualTo 0
        semaphore.initialize()
        semaphore.availablePermits() shouldBeEqualTo 2

        semaphore.tryAcquire(2).shouldBeTrue()
        semaphore.availablePermits() shouldBeEqualTo 0
        semaphore.tryAcquire().shouldBeFalse()
        assertFailsWith<IllegalStateException> {
            semaphore.acquire(waitTime = 100.milliseconds)
        }

        semaphore.release()
        semaphore.availablePermits() shouldBeEqualTo 1
        semaphore.release(2)
        semaphore.availablePermits() shouldBeEqualTo 2

        semaphore.trySetPermits(1)
        semaphore.acquire(waitTime = 100.milliseconds)
        semaphore.availablePermits() shouldBeEqualTo 0
    }

    @Test
    fun `async semaphore 는 permits 획득 해제 timeout 을 처리한다`() {
        val semaphore = LettuceSemaphore(connection, semaphoreKey(), totalPermits = 1)
        semaphore.initialize()

        semaphore.tryAcquireAsync().get().shouldBeTrue()
        semaphore.tryAcquireAsync().get().shouldBeFalse()
        assertFailsWith<ExecutionException> {
            semaphore.acquireAsync(waitTime = 100.milliseconds).get()
        }

        semaphore.releaseAsync().get()
        semaphore.availablePermits() shouldBeEqualTo 1
        semaphore.acquireAsync(waitTime = 100.milliseconds).get()
        semaphore.availablePermits() shouldBeEqualTo 0
    }

    @Test
    fun `suspend semaphore 는 permits 획득 해제 timeout 을 처리한다`() = runSuspendIO {
        val semaphore = LettuceSuspendSemaphore(connection, semaphoreKey(), totalPermits = 2)

        semaphore.availablePermits() shouldBeEqualTo 0
        semaphore.initialize()
        semaphore.availablePermits() shouldBeEqualTo 2

        semaphore.tryAcquire(2).shouldBeTrue()
        semaphore.availablePermits() shouldBeEqualTo 0
        semaphore.tryAcquire().shouldBeFalse()
        assertFailsWith<IllegalStateException> {
            semaphore.acquire(waitTime = 100.milliseconds)
        }

        semaphore.release()
        semaphore.availablePermits() shouldBeEqualTo 1
        semaphore.release(2)
        semaphore.availablePermits() shouldBeEqualTo 2

        semaphore.trySetPermits(1)
        semaphore.acquire(waitTime = 100.milliseconds)
        semaphore.availablePermits() shouldBeEqualTo 0
    }
}
