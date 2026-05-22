package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.etcd.internal.EtcdAcquisitionDeadline
import io.bluetape4k.leader.etcd.internal.EtcdBackendErrorClassifier
import io.bluetape4k.leader.etcd.internal.EtcdLeaseHandle
import io.bluetape4k.leader.etcd.internal.EtcdLeaseTime
import io.bluetape4k.leader.etcd.internal.EtcdLockClient
import io.bluetape4k.leader.etcd.internal.EtcdLockExtendDelegate
import io.bluetape4k.leader.etcd.internal.JetcdEtcdLockClient
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * etcd v3 single-leader election backed by jetcd Lock service and leases.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by this elector.
 */
class EtcdLeaderElector private constructor(
    private val lockClient: EtcdLockClient,
    val options: EtcdLeaderElectionOptions,
) : LeaderElector {

    companion object: KLogging() {
        internal const val ETCD_FACTORY_BEAN_NAME = "etcd-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(EtcdBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: Client,
            options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
        ): EtcdLeaderElector =
            EtcdLeaderElector(JetcdEtcdLockClient(client, options.keyPrefix), options)

        internal fun create(
            lockClient: EtcdLockClient,
            options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
        ): EtcdLeaderElector =
            EtcdLeaderElector(lockClient, options)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val leaseHandle = acquire(lockName) ?: return null
        val delegate = EtcdLockExtendDelegate(lockClient, leaseHandle)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = ETCD_FACTORY_BEAN_NAME,
            ),
            token = leaseHandle.token,
            acquiredAtNanos = leaseHandle.acquiredAtNanos,
            extendDelegate = delegate,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            enabled = options.leaderOptions.autoExtend,
            leaseTime = options.leaderOptions.leaseTime,
            delegate = delegate,
            classifier = ERROR_CLASSIFIER,
        )

        return try {
            AopScopeAccess.withPushedSync(handle) { action() }
        } finally {
            watchdog.close()
            releaseAfterMinLease(leaseHandle)
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.supplyAsync(
            { runIfLeader(lockName) { action().join() } },
            executor,
        )

    private fun acquire(lockName: String): EtcdLeaseHandle? {
        val ttlSeconds = EtcdLeaseTime.ttlSeconds(options.leaderOptions.leaseTime)
        val deadline = EtcdAcquisitionDeadline.fromNow(options.leaderOptions.waitTime)
        val leaseId = try {
            lockClient.grantLease(ttlSeconds).get(deadline.remainingMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: TimeoutException) {
            log.debug { "etcd lease grant timed out. lockName=$lockName" }
            return null
        } catch (e: Exception) {
            log.warn(e) { "etcd lease grant failed. lockName=$lockName" }
            return null
        }

        val lockKey = lockClient.singleLockKey(lockName)
        val lockFuture = lockClient.lock(lockKey, leaseId)
        val ownershipKey = try {
            lockFuture.get(deadline.remainingMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            scheduleLateCleanup(lockFuture, leaseId)
            lockFuture.cancel(true)
            revokeLease(leaseId)
            return null
        } catch (e: TimeoutException) {
            scheduleLateCleanup(lockFuture, leaseId)
            lockFuture.cancel(true)
            revokeLease(leaseId)
            log.debug { "etcd leader lock acquisition timed out. lockName=$lockName" }
            return null
        } catch (e: Exception) {
            revokeLease(leaseId)
            log.warn(e) { "etcd leader lock acquisition failed. lockName=$lockName" }
            return null
        }

        return EtcdLeaseHandle(
            leaseId = leaseId,
            lockName = lockName,
            ownershipKey = ownershipKey,
        )
    }

    private fun releaseAfterMinLease(handle: EtcdLeaseHandle) {
        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderOptions.minLeaseTime)
        if (remaining > Duration.ZERO) {
            runCatching { Thread.sleep(remaining.inWholeMilliseconds.coerceAtLeast(1L)) }
                .onFailure { e ->
                    if (e is InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
        }
        release(handle)
    }

    private fun release(handle: EtcdLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }
        unlock(handle.ownershipKey)
        revokeLease(handle.leaseId)
    }

    private fun scheduleLateCleanup(lockFuture: CompletableFuture<ByteSequence>, leaseId: Long) {
        lockFuture.whenComplete { ownershipKey, failure ->
            if (failure == null && ownershipKey != null && !ownershipKey.isEmpty) {
                unlock(ownershipKey)
            }
            revokeLease(leaseId)
        }
    }

    private fun unlock(ownershipKey: ByteSequence) {
        runCatching { lockClient.unlock(ownershipKey).get(10, TimeUnit.SECONDS) }
            .onFailure { e ->
                if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                    log.debug { "etcd unlock skipped because key is already gone." }
                } else {
                    log.warn(e) { "etcd unlock failed." }
                }
            }
    }

    private fun revokeLease(leaseId: Long) {
        runCatching { lockClient.revokeLease(leaseId).get(10, TimeUnit.SECONDS) }
            .onFailure { e ->
                if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                    log.debug { "etcd lease revoke skipped because lease is already gone. leaseId=$leaseId" }
                } else {
                    log.warn(e) { "etcd lease revoke failed. leaseId=$leaseId" }
                }
            }
    }
}

inline fun <T> Client.runIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    EtcdLeaderElector(this, options).runIfLeader(lockName) { action() }

fun <T> Client.runAsyncIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    EtcdLeaderElector(this, options).runAsyncIfLeader(lockName, executor, action)
