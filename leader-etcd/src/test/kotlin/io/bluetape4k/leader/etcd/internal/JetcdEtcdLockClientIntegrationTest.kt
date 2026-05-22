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
    fun `lock client grants keeps alive unlocks and revokes a real etcd lease`() {
        newClient().use { client ->
            val lockClient = JetcdEtcdLockClient(client, keyPrefix = "/bluetape4k/leader/test/${randomName()}")
            val leaseId = lockClient.grantLease(10L).get(10, TimeUnit.SECONDS)
            val lockKey = lockClient.singleLockKey("daily:batch")
            val ownershipKey = lockClient.lock(lockKey, leaseId).get(10, TimeUnit.SECONDS)

            try {
                ownershipKey.isEmpty.shouldBeFalse()
                ownershipKey.toString(StandardCharsets.UTF_8)
                    .startsWith(lockKey.toString(StandardCharsets.UTF_8))
                    .shouldBeTrue()

                val keepAlive = lockClient.keepAliveOnce(leaseId).get(10, TimeUnit.SECONDS)
                keepAlive.getID() shouldBeEqualTo leaseId
            } finally {
                lockClient.unlockIfPresent(ownershipKey)
                lockClient.revokeIfPresent(leaseId)
            }
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
