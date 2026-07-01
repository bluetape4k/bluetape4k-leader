package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.redisson.AbstractRedissonLeaderTest
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.api.RFuture
import org.redisson.api.RKeys
import org.redisson.api.RScript
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class RedissonOwnerAtomicExtendDelegateTest: AbstractRedissonLeaderTest() {

    private val scriptClient = mockk<RedissonClient>()
    private val keys = mockk<RKeys>()
    private val script = mockk<RScript>()
    private val scriptResult = mockk<RFuture<Long>>()

    @BeforeEach
    fun beforeEach() {
        clearMocks(scriptClient, keys, script, scriptResult)

        every { scriptClient.keys } returns keys
        every { scriptClient.getScript(StringCodec.INSTANCE) } returns script
    }

    @Test
    fun `sync extend returns WrongThread when atomic owner field check fails`() {
        val lockName = "redisson-owner-atomic-sync"
        val ownerId = 101L
        val lock = AbstractRedissonLeaderTest.redissonClient.getLock(lockName)

        every {
            script.eval<Long>(
                lockName,
                RScript.Mode.READ_WRITE,
                any(),
                RScript.ReturnType.LONG,
                any<List<Any>>(),
                any(),
                any(),
            )
        } returns RedissonOwnerAtomicExtend.WRONG_THREAD_RESULT

        val outcome = RedissonLockExtendDelegate(scriptClient, lock, ownerId)
            .extend(30.seconds)

        outcome.shouldBeInstanceOf<ExtendOutcome.WrongThread>()
        verify(exactly = 0) { keys.expire(any<java.time.Duration>(), lockName) }
    }

    @Test
    fun `suspend extend returns NotHeld when owner key disappears during atomic extend`() = runSuspendIO {
        val lockName = "redisson-owner-atomic-suspend"
        val ownerId = 202L
        val lock = AbstractRedissonLeaderTest.redissonClient.getLock(lockName)

        every { scriptResult.toCompletableFuture() } returns CompletableFuture.completedFuture(
            RedissonOwnerAtomicExtend.NOT_HELD_RESULT,
        )
        every {
            script.evalAsync<Long>(
                lockName,
                RScript.Mode.READ_WRITE,
                any(),
                RScript.ReturnType.LONG,
                any<List<Any>>(),
                any(),
                any(),
            )
        } returns scriptResult

        val outcome = RedissonSuspendLockExtendDelegate(scriptClient, lock, ownerId)
            .extendSuspend(30.seconds)

        outcome.shouldBeInstanceOf<ExtendOutcome.NotHeld>()
        verify(exactly = 0) { keys.expireAsync(any<java.time.Duration>(), lockName) }
    }
}
