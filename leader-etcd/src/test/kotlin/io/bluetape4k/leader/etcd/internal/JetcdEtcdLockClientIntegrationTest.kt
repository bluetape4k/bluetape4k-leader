package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.etcd.AbstractEtcdLeaderTest
import io.etcd.jetcd.ByteSequence
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class JetcdEtcdLockClientIntegrationTest: AbstractEtcdLeaderTest() {

    @Test
    fun `lock keys are built with encoded path segments and usable by real etcd`() {
        newClient().use { client ->
            val keyPrefix = "/bluetape4k/leader/test/${randomName()}"
            val lockClient = JetcdEtcdLockClient(client, keyPrefix = keyPrefix)
            val singleLockKey = lockClient.singleLockKey("batch:daily")
            val groupSlotLockKey = lockClient.groupSlotLockKey("batch_job", 2)
            val singleLeaseId = lockClient.grantLease(10L).get(10, TimeUnit.SECONDS)
            val groupLeaseId = lockClient.grantLease(10L).get(10, TimeUnit.SECONDS)
            var singleOwnershipKey = ByteSequence.EMPTY
            var groupOwnershipKey = ByteSequence.EMPTY

            try {
                singleLockKey.toString(StandardCharsets.UTF_8) shouldBeEqualTo
                    "$keyPrefix/single/batch%3Adaily"
                groupSlotLockKey.toString(StandardCharsets.UTF_8) shouldBeEqualTo
                    "$keyPrefix/group/batch_job/slot-2"

                singleOwnershipKey = lockClient.lock(singleLockKey, singleLeaseId).get(10, TimeUnit.SECONDS)
                groupOwnershipKey = lockClient.lock(groupSlotLockKey, groupLeaseId).get(10, TimeUnit.SECONDS)

                singleOwnershipKey.shouldStartWith(singleLockKey)
                groupOwnershipKey.shouldStartWith(groupSlotLockKey)
            } finally {
                lockClient.unlockIfPresent(groupOwnershipKey)
                lockClient.unlockIfPresent(singleOwnershipKey)
                lockClient.revokeIfPresent(groupLeaseId)
                lockClient.revokeIfPresent(singleLeaseId)
            }
        }
    }

    @Test
    fun `lock client grants keeps alive unlocks and revokes a real etcd lease`() {
        newClient().use { client ->
            val lockClient = JetcdEtcdLockClient(client, keyPrefix = "/bluetape4k/leader/test/${randomName()}")
            val leaseId = lockClient.grantLease(10L).get(10, TimeUnit.SECONDS)
            val lockKey = lockClient.singleLockKey("daily:batch")
            val ownershipKey = lockClient.lock(lockKey, leaseId).get(10, TimeUnit.SECONDS)

            try {
                ownershipKey.isEmpty.shouldBeFalse()
                ownershipKey.shouldStartWith(lockKey)

                val keepAlive = lockClient.keepAliveOnce(leaseId).get(10, TimeUnit.SECONDS)
                keepAlive.getID() shouldBeEqualTo leaseId
            } finally {
                lockClient.unlockIfPresent(ownershipKey)
                lockClient.revokeIfPresent(leaseId)
            }
        }
    }

    @Test
    fun `invalid arguments are rejected before real etcd calls`() {
        newClient().use { client ->
            val lockClient = JetcdEtcdLockClient(client, keyPrefix = "/bluetape4k/leader/test/${randomName()}")
            val key = ByteSequence.from("key", StandardCharsets.UTF_8)

            assertFailsWith<IllegalArgumentException> { lockClient.grantLease(0L) }
            assertFailsWith<IllegalArgumentException> { lockClient.lock(ByteSequence.EMPTY, 1L) }
            assertFailsWith<IllegalArgumentException> { lockClient.lock(key, 0L) }
            assertFailsWith<IllegalArgumentException> { lockClient.unlock(ByteSequence.EMPTY) }
            assertFailsWith<IllegalArgumentException> { lockClient.revokeLease(0L) }
            assertFailsWith<IllegalArgumentException> { lockClient.keepAliveOnce(0L) }
        }
    }

    @Test
    fun `second lock waits until first ownership key is unlocked`() {
        newClient().use { client ->
            val lockClient = JetcdEtcdLockClient(client, keyPrefix = "/bluetape4k/leader/test/${randomName()}")
            val lockKey = lockClient.singleLockKey("exclusive-job")
            val firstLeaseId = lockClient.grantLease(10L).get(10, TimeUnit.SECONDS)
            val secondLeaseId = lockClient.grantLease(10L).get(10, TimeUnit.SECONDS)
            val firstOwnershipKey = lockClient.lock(lockKey, firstLeaseId).get(10, TimeUnit.SECONDS)
            var secondOwnershipKey = ByteSequence.EMPTY

            try {
                val pendingSecondLock = lockClient.lock(lockKey, secondLeaseId)

                assertFailsWith<TimeoutException> {
                    pendingSecondLock.get(200, TimeUnit.MILLISECONDS)
                }

                lockClient.unlock(firstOwnershipKey).get(10, TimeUnit.SECONDS)
                secondOwnershipKey = pendingSecondLock.get(10, TimeUnit.SECONDS)
                secondOwnershipKey.isEmpty.shouldBeFalse()
            } finally {
                lockClient.unlockIfPresent(secondOwnershipKey)
                lockClient.unlockIfPresent(firstOwnershipKey)
                lockClient.revokeIfPresent(secondLeaseId)
                lockClient.revokeIfPresent(firstLeaseId)
            }
        }
    }

    private fun ByteSequence.shouldStartWith(lockKey: ByteSequence) {
        toString(StandardCharsets.UTF_8)
            .startsWith(lockKey.toString(StandardCharsets.UTF_8))
            .shouldBeTrue()
    }

    private fun JetcdEtcdLockClient.unlockIfPresent(ownershipKey: ByteSequence) {
        if (!ownershipKey.isEmpty) {
            runCatching { unlock(ownershipKey).get(10, TimeUnit.SECONDS) }
        }
    }

    private fun JetcdEtcdLockClient.revokeIfPresent(leaseId: Long) {
        if (leaseId > 0L) {
            runCatching { revokeLease(leaseId).get(10, TimeUnit.SECONDS) }
        }
    }
}
