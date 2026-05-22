package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.etcd.internal.EtcdAcquisitionDeadline
import io.bluetape4k.leader.etcd.internal.EtcdBackendErrorClassifier
import io.bluetape4k.leader.etcd.internal.EtcdLeaseHandle
import io.bluetape4k.leader.etcd.internal.EtcdLeaseTime
import io.bluetape4k.leader.etcd.internal.EtcdLockClient
import io.bluetape4k.leader.etcd.internal.EtcdSuspendLockExtendDelegate
import io.bluetape4k.leader.etcd.internal.JetcdEtcdLockClient
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

/**
 * Coroutine-native etcd v3 single-leader election backed by jetcd Lock service and leases.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by this elector.
 */
class EtcdSuspendLeaderElector private constructor(
    private val lockClient: EtcdLockClient,
    val options: EtcdLeaderElectionOptions,
) : SuspendLeaderElector {

    companion object: KLoggingChannel() {
        internal const val ETCD_SUSPEND_FACTORY_BEAN_NAME = "etcd-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(EtcdBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: Client,
            options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
        ): EtcdSuspendLeaderElector =
            EtcdSuspendLeaderElector(JetcdEtcdLockClient(client, options.keyPrefix), options)

        internal fun create(
            lockClient: EtcdLockClient,
            options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
        ): EtcdSuspendLeaderElector =
            EtcdSuspendLeaderElector(lockClient, options)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val leaseHandle = acquire(lockName) ?: return null
        val delegate: SuspendExtendDelegate = EtcdSuspendLockExtendDelegate(lockClient, leaseHandle)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = ETCD_SUSPEND_FACTORY_BEAN_NAME,
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

        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            withContext(NonCancellable) {
                watchdog.close()
                releaseAfterMinLease(leaseHandle)
            }
        }
    }

    private suspend fun acquire(lockName: String): EtcdLeaseHandle? {
        var leaseId = 0L
        var lockFuture: CompletableFuture<ByteSequence>? = null

        try {
            val ttlSeconds = EtcdLeaseTime.ttlSeconds(options.leaderOptions.leaseTime)
            val deadline = EtcdAcquisitionDeadline.fromNow(options.leaderOptions.waitTime)
            leaseId = withTimeoutOrNull(deadline.remainingDuration()) {
                lockClient.grantLease(ttlSeconds).await()
            } ?: run {
                log.debug { "etcd suspend lease grant timed out. lockName=$lockName" }
                return null
            }
            lockFuture = lockClient.lock(lockClient.singleLockKey(lockName), leaseId)
            val ownershipKey = withTimeoutOrNull(deadline.remainingDuration()) { lockFuture.await() }

            if (ownershipKey == null) {
                scheduleLateCleanup(lockFuture, leaseId)
                lockFuture.cancel(true)
                revokeLease(leaseId)
                log.debug { "etcd suspend leader lock acquisition timed out. lockName=$lockName" }
                return null
            }

            return EtcdLeaseHandle(
                leaseId = leaseId,
                lockName = lockName,
                ownershipKey = ownershipKey,
            )
        } catch (e: CancellationException) {
            lockFuture?.let { scheduleLateCleanup(it, leaseId) }
            lockFuture?.cancel(true)
            if (leaseId > 0L) {
                revokeLease(leaseId)
            }
            throw e
        } catch (e: Exception) {
            if (leaseId > 0L) {
                revokeLease(leaseId)
            }
            log.warn(e) { "etcd suspend leader lock acquisition failed. lockName=$lockName" }
            return null
        }
    }

    private suspend fun releaseAfterMinLease(handle: EtcdLeaseHandle) {
        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderOptions.minLeaseTime)
        if (remaining > Duration.ZERO) {
            delay(remaining)
        }
        release(handle)
    }

    private suspend fun release(handle: EtcdLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }
        unlock(handle.ownershipKey)
        revokeLease(handle.leaseId)
    }

    private fun scheduleLateCleanup(lockFuture: CompletableFuture<ByteSequence>, leaseId: Long) {
        lockFuture.whenComplete { ownershipKey, failure ->
            if (failure == null && ownershipKey != null && !ownershipKey.isEmpty) {
                lockClient.unlock(ownershipKey)
                    .thenCompose { lockClient.revokeLease(leaseId) }
                    .exceptionally { null }
            } else {
                lockClient.revokeLease(leaseId).exceptionally { null }
            }
        }
    }

    private suspend fun unlock(ownershipKey: ByteSequence) {
        try {
            lockClient.unlock(ownershipKey).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                log.debug { "etcd suspend unlock skipped because key is already gone." }
            } else {
                log.warn(e) { "etcd suspend unlock failed." }
            }
        }
    }

    private suspend fun revokeLease(leaseId: Long) {
        try {
            lockClient.revokeLease(leaseId).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                log.debug { "etcd suspend lease revoke skipped because lease is already gone. leaseId=$leaseId" }
            } else {
                log.warn(e) { "etcd suspend lease revoke failed. leaseId=$leaseId" }
            }
        }
    }

}

suspend inline fun <T> Client.suspendRunIfLeader(
    lockName: String,
    options: EtcdLeaderElectionOptions = EtcdLeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    EtcdSuspendLeaderElector(this, options).runIfLeader(lockName) { action() }
