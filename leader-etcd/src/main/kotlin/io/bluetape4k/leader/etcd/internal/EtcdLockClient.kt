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
 * etcd backend leader election 계약을 설명하는 한국어 KDoc입니다.
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
 * `JetcdEtcdLockClient`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property client etcd backend 호출과 상태 계산에 사용하는 속성입니다.
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
