package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.support.requirePositiveNumber
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.lease.LeaseKeepAliveResponse
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.jetcd.options.GetOption.SortTarget
import java.util.concurrent.CompletableFuture

/** etcd 단일/group key 계산, lease 부여, lock acquire/release, keep-alive를 추상화한 클라이언트입니다. */
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
 * `JetcdEtcdLockClient`는 jetcd의 lease, lock, KV API를 [EtcdLockClient] 계약으로 연결합니다.
 *
 * @property client lease·lock·KV API를 호출하는 jetcd client입니다. 인증과 endpoint 설정은 client가 소유합니다.
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
        ttlSeconds.requirePositiveNumber("ttlSeconds")
        return client.leaseClient.grant(ttlSeconds).thenApply { it.id }
    }

    override fun lock(lockKey: ByteSequence, leaseId: Long): CompletableFuture<ByteSequence> {
        require(!lockKey.isEmpty) { "lockKey must not be empty." }
        leaseId.requirePositiveNumber("leaseId")
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
        leaseId.requirePositiveNumber("leaseId")
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
        ByteSequence.from(path, Charsets.UTF_8)
}
