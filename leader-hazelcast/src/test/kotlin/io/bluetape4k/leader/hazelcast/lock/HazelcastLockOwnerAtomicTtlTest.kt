package io.bluetape4k.leader.hazelcast.lock

import com.hazelcast.map.IMap
import com.hazelcast.transaction.TransactionContext
import com.hazelcast.transaction.TransactionalMap
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.ExtendOutcome
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastLockOwnerAtomicTtlTest {

    private val mapName = "owner-atomic-locks"
    private val lockMap = mockk<IMap<String, String>>(relaxed = true)
    private val suspendLockMap = mockk<IMap<String, String>>(relaxed = true)
    private val transactionContext = mockk<TransactionContext>(relaxed = true)
    private val suspendTransactionContext = mockk<TransactionContext>(relaxed = true)
    private val transactionMap = mockk<TransactionalMap<String, String>>(relaxed = true)
    private val suspendTransactionMap = mockk<TransactionalMap<String, String>>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(lockMap, suspendLockMap, transactionContext, suspendTransactionContext, transactionMap, suspendTransactionMap)
    }

    @Test
    fun `unlock minLeaseTime rewrite is guarded by Hazelcast transaction`() {
        val lockKey = "hazelcast-owner-atomic-unlock"
        val lock = HazelcastLock(lockMap, lockKey, mapName) { transactionContext }
        val token = lock.tokenForTest()
        val acquiredAtNanos = System.nanoTime()

        prepareTransaction(lockKey, token)
        every { transactionMap.put(lockKey, token, any<Long>(), TimeUnit.MILLISECONDS) } returns token

        lock.unlock(30.seconds, acquiredAtNanos)

        verifyOrder {
            transactionContext.beginTransaction()
            transactionContext.getMap<String, String>(mapName)
            transactionMap.getForUpdate(lockKey)
            transactionMap.put(lockKey, token, any<Long>(), TimeUnit.MILLISECONDS)
            transactionContext.commitTransaction()
        }
        verify(exactly = 0) { lockMap.remove(lockKey, token) }
    }

    @Test
    fun `unlock minLeaseTime takeover returns NotHeld and does not rewrite successor token`() {
        val lockKey = "hazelcast-owner-atomic-unlock-takeover"
        val lock = HazelcastLock(lockMap, lockKey, mapName) { transactionContext }
        val successorToken = "successor-token"

        prepareTransaction(lockKey, successorToken)

        lock.unlock(30.seconds, System.nanoTime())

        verifyOrder {
            transactionContext.beginTransaction()
            transactionContext.getMap<String, String>(mapName)
            transactionMap.getForUpdate(lockKey)
            transactionContext.commitTransaction()
        }
        verify(exactly = 0) { transactionMap.put(lockKey, any<String>(), any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { transactionMap.remove(lockKey) }
        verify(exactly = 0) { lockMap.set(lockKey, any<String>(), any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { lockMap.remove(lockKey, any<String>()) }
    }

    @Test
    fun `extendDetailed TTL rewrite is guarded by Hazelcast transaction`() {
        val lockKey = "hazelcast-owner-atomic-extend"
        val lock = HazelcastLock(lockMap, lockKey, mapName) { transactionContext }
        val token = lock.tokenForTest()

        prepareTransaction(lockKey, token)
        every { transactionMap.put(lockKey, token, 30.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS) } returns token

        val outcome = lock.extendDetailed(30.seconds)

        outcome.shouldBeInstanceOf<ExtendOutcome.Extended>()
        verifyOrder {
            transactionContext.beginTransaction()
            transactionContext.getMap<String, String>(mapName)
            transactionMap.getForUpdate(lockKey)
            transactionMap.put(lockKey, token, 30.seconds.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            transactionContext.commitTransaction()
        }
        verify(exactly = 0) { lockMap.replace(lockKey, token, token) }
        verify(exactly = 0) { lockMap.setTtl(lockKey, any<Long>(), TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `extendDetailed takeover returns NotHeld and does not refresh successor TTL`() {
        val lockKey = "hazelcast-owner-atomic-extend-takeover"
        val lock = HazelcastLock(lockMap, lockKey, mapName) { transactionContext }
        val successorToken = "successor-token"

        prepareTransaction(lockKey, successorToken)

        val outcome = lock.extendDetailed(30.seconds)

        outcome shouldBeEqualTo ExtendOutcome.NotHeld
        verifyOrder {
            transactionContext.beginTransaction()
            transactionContext.getMap<String, String>(mapName)
            transactionMap.getForUpdate(lockKey)
            transactionContext.commitTransaction()
        }
        verify(exactly = 0) { transactionMap.put(lockKey, any<String>(), any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { lockMap.setTtl(lockKey, any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { lockMap.replace(lockKey, any<String>(), any<String>()) }
    }

    @Test
    fun `suspend extendDetailed takeover returns NotHeld and does not refresh successor TTL`() = runSuspendIO {
        val lockKey = "hazelcast-owner-atomic-suspend-extend-takeover"
        val lock = HazelcastSuspendLock(suspendLockMap, lockKey, mapName) { suspendTransactionContext }
        val successorToken = "successor-token"

        prepareSuspendTransaction(lockKey, successorToken)

        val outcome = lock.extendDetailed(30.seconds)

        outcome shouldBeEqualTo ExtendOutcome.NotHeld
        verifyOrder {
            suspendTransactionContext.beginTransaction()
            suspendTransactionContext.getMap<String, String>(mapName)
            suspendTransactionMap.getForUpdate(lockKey)
            suspendTransactionContext.commitTransaction()
        }
        verify(exactly = 0) { suspendTransactionMap.put(lockKey, any<String>(), any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { suspendLockMap.setTtl(lockKey, any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { suspendLockMap.replace(lockKey, any<String>(), any<String>()) }
    }

    @Test
    fun `suspend unlock minLeaseTime takeover does not rewrite successor token`() = runSuspendIO {
        val lockKey = "hazelcast-owner-atomic-suspend-unlock-takeover"
        val lock = HazelcastSuspendLock(suspendLockMap, lockKey, mapName) { suspendTransactionContext }
        val successorToken = "successor-token"

        prepareSuspendTransaction(lockKey, successorToken)

        lock.unlock(30.seconds, System.nanoTime())

        verifyOrder {
            suspendTransactionContext.beginTransaction()
            suspendTransactionContext.getMap<String, String>(mapName)
            suspendTransactionMap.getForUpdate(lockKey)
            suspendTransactionContext.commitTransaction()
        }
        verify(exactly = 0) { suspendTransactionMap.put(lockKey, any<String>(), any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { suspendTransactionMap.remove(lockKey) }
        verify(exactly = 0) { suspendLockMap.set(lockKey, any<String>(), any<Long>(), TimeUnit.MILLISECONDS) }
        verify(exactly = 0) { suspendLockMap.remove(lockKey, any<String>()) }
    }

    private fun prepareTransaction(lockKey: String, observedToken: String) {
        every { transactionContext.beginTransaction() } just Runs
        every { transactionContext.getMap<String, String>(mapName) } returns transactionMap
        every { transactionMap.getForUpdate(lockKey) } returns observedToken
        every { transactionContext.commitTransaction() } just Runs
        every { transactionContext.rollbackTransaction() } just Runs
    }

    private fun prepareSuspendTransaction(lockKey: String, observedToken: String) {
        every { suspendTransactionContext.beginTransaction() } just Runs
        every { suspendTransactionContext.getMap<String, String>(mapName) } returns suspendTransactionMap
        every { suspendTransactionMap.getForUpdate(lockKey) } returns observedToken
        every { suspendTransactionContext.commitTransaction() } just Runs
        every { suspendTransactionContext.rollbackTransaction() } just Runs
    }

    private fun HazelcastLock.tokenForTest(): String {
        val tokenField = HazelcastLock::class.java.getDeclaredField("token")
        tokenField.isAccessible = true
        return tokenField.get(this) as String
    }
}
