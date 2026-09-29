package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.leader.etcd.support.toByteSequence
import io.bluetape4k.leader.etcd.support.toUtf8String
import io.bluetape4k.logging.KLogging
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.Lease
import io.etcd.jetcd.Lock
import io.etcd.jetcd.lease.LeaseGrantResponse
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.lease.LeaseRevokeResponse
import io.etcd.jetcd.lock.LockResponse
import io.etcd.jetcd.lock.UnlockResponse
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JetcdEtcdLockClientTest {

    companion object: KLogging()

    private val client = mockk<Client>(relaxed = true)
    private val lease = mockk<Lease>()
    private val lock = mockk<Lock>()

    @BeforeEach
    fun setUp() {
        clearMocks(client, lease, lock)
        every { client.leaseClient } returns lease
        every { client.lockClient } returns lock
    }

    @Test
    fun `lock keys are built with encoded path segments`() {
        val lockClient = JetcdEtcdLockClient(client)

        lockClient.singleLockKey("batch:daily")
            .toUtf8String() shouldBeEqualTo "/bluetape4k/leader/single/batch%3Adaily"

        lockClient.groupSlotLockKey("batch_job", 2)
            .toUtf8String() shouldBeEqualTo "/bluetape4k/leader/group/batch_job/slot-2"
    }

    @Test
    fun `grant lock unlock revoke and keepalive delegate to jetcd clients`() {
        val lockClient = JetcdEtcdLockClient(client)
        val grantResponse = mockk<LeaseGrantResponse>()
        val lockResponse = mockk<LockResponse>()
        val revokeResponse = mockk<LeaseRevokeResponse>()
        val unlockResponse = mockk<UnlockResponse>()
        val keepAliveResponse = mockk<LeaseKeepAliveResponse>()

        val lockKey = lockClient.singleLockKey("job")
        val ownershipKey = "/locks/owner".toByteSequence()

        every { grantResponse.id } returns 11L
        every { lockResponse.key } returns ownershipKey
        every { lease.grant(5L) } returns completableFutureOf(grantResponse)
        every { lock.lock(lockKey, 11L) } returns completableFutureOf(lockResponse)
        every { lock.unlock(ownershipKey) } returns completableFutureOf(unlockResponse)
        every { lease.revoke(11L) } returns completableFutureOf(revokeResponse)
        every { lease.keepAliveOnce(11L) } returns completableFutureOf(keepAliveResponse)

        lockClient.grantLease(5L).get() shouldBeEqualTo 11L
        lockClient.lock(lockKey, 11L).get() shouldBeEqualTo ownershipKey
        lockClient.unlock(ownershipKey).get() shouldBeEqualTo Unit
        lockClient.revokeLease(11L).get() shouldBeEqualTo Unit
        lockClient.keepAliveOnce(11L).get() shouldBeEqualTo keepAliveResponse

        verify(exactly = 0) { client.close() }
        verify {
            lease.grant(5L)
            lock.lock(lockKey, 11L)
            lock.unlock(ownershipKey)
            lease.revoke(11L)
            lease.keepAliveOnce(11L)
        }
        confirmVerified(lease, lock)
    }

    @Test
    fun `invalid arguments are rejected before jetcd calls`() {
        val lockClient = JetcdEtcdLockClient(client)
        val key = "key".toByteSequence()

        assertFailsWith<IllegalArgumentException> { lockClient.grantLease(0L) }
        assertFailsWith<IllegalArgumentException> { lockClient.lock(ByteSequence.EMPTY, 1L) }
        assertFailsWith<IllegalArgumentException> { lockClient.lock(key, 0L) }
        assertFailsWith<IllegalArgumentException> { lockClient.unlock(ByteSequence.EMPTY) }
        assertFailsWith<IllegalArgumentException> { lockClient.revokeLease(0L) }
        assertFailsWith<IllegalArgumentException> { lockClient.keepAliveOnce(0L) }
    }
}
