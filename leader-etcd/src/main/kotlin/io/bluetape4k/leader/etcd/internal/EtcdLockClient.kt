package io.bluetape4k.leader.etcd.internal

import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.jetcd.options.GetOption.SortTarget
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/**
 * Narrow boundary over jetcd lease and lock clients.
 */
internal interface EtcdLockClient {

    fun singleLockKey(lockName: String): ByteSequence

    fun groupSlotLockKey(lockName: String, zeroBasedSlot: Int): ByteSequence

    fun grantLease(ttlSeconds: Long): CompletableFuture<Long>

    fun lock(lockKey: ByteSequence, leaseId: Long): CompletableFuture<ByteSequence>

    fun unlock(ownershipKey: ByteSequence): CompletableFuture<Unit>

    fun revokeLease(leaseId: Long): CompletableFuture<Unit>

    fun keepAliveOnce(leaseId: Long): CompletableFuture<LeaseKeepAliveResponse>

    fun ownershipKeys(lockKey: ByteSequence): CompletableFuture<List<ByteSequence>>
}

/**
 * jetcd-backed [EtcdLockClient].
 *
 * This wrapper never closes the supplied [client]; the caller owns the jetcd client lifecycle.
 */
internal class JetcdEtcdLockClient(
    private val client: Client,
    keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
): EtcdLockClient {

    private val paths = EtcdLeaderPaths(keyPrefix)

    override fun singleLockKey(lockName: String): ByteSequence =
        byteSequence(paths.single(lockName))

    override fun groupSlotLockKey(lockName: String, zeroBasedSlot: Int): ByteSequence =
        byteSequence(paths.groupSlot(lockName, zeroBasedSlot))

    override fun grantLease(ttlSeconds: Long): CompletableFuture<Long> {
        require(ttlSeconds > 0L) { "ttlSeconds must be positive. ttlSeconds=$ttlSeconds" }
        return client.leaseClient.grant(ttlSeconds).thenApply { it.id }
    }

    override fun lock(lockKey: ByteSequence, leaseId: Long): CompletableFuture<ByteSequence> {
        require(!lockKey.isEmpty) { "lockKey must not be empty." }
        require(leaseId > 0L) { "leaseId must be positive. leaseId=$leaseId" }
        return client.lockClient.lock(lockKey, leaseId).thenApply { it.key }
    }

    override fun unlock(ownershipKey: ByteSequence): CompletableFuture<Unit> {
        require(!ownershipKey.isEmpty) { "ownershipKey must not be empty." }
        return client.lockClient.unlock(ownershipKey).thenApply { }
    }

    override fun revokeLease(leaseId: Long): CompletableFuture<Unit> {
        require(leaseId > 0L) { "leaseId must be positive. leaseId=$leaseId" }
        return client.leaseClient.revoke(leaseId).thenApply { }
    }

    override fun keepAliveOnce(leaseId: Long): CompletableFuture<LeaseKeepAliveResponse> {
        require(leaseId > 0L) { "leaseId must be positive. leaseId=$leaseId" }
        return client.leaseClient.keepAliveOnce(leaseId)
    }

    override fun ownershipKeys(lockKey: ByteSequence): CompletableFuture<List<ByteSequence>> {
        require(!lockKey.isEmpty) { "lockKey must not be empty." }
        val option = GetOption.builder()
            .isPrefix(true)
            .withSortField(SortTarget.CREATE)
            .withSortOrder(SortOrder.ASCEND)
            .withLimit(1)
            .build()
        return client.kvClient.get(lockKey, option)
            .thenApply { response -> response.kvs.map { it.key } }
    }

    private fun byteSequence(path: String): ByteSequence =
        ByteSequence.from(path, StandardCharsets.UTF_8)
}
