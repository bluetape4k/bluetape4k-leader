package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
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
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coroutine-native etcd v3 multi-leader election backed by one jetcd Lock lease per group slot.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by this elector.
 */
class EtcdSuspendLeaderGroupElector private constructor(
    private val lockClient: EtcdLockClient,
    val options: EtcdLeaderGroupElectionOptions,
) : SuspendLeaderGroupElector {

    companion object: KLoggingChannel() {
        internal const val ETCD_SUSPEND_GROUP_FACTORY_BEAN_NAME = "etcd-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(EtcdBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: Client,
            options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
        ): EtcdSuspendLeaderGroupElector =
            EtcdSuspendLeaderGroupElector(JetcdEtcdLockClient(client, options.keyPrefix), options)

        internal fun create(
            lockClient: EtcdLockClient,
            options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
        ): EtcdSuspendLeaderGroupElector =
            EtcdSuspendLeaderGroupElector(lockClient, options)
    }

    override val maxLeaders: Int = options.maxLeaders

    override fun activeCount(lockName: String): Int =
        currentLeaders(lockName).size

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState {
        val leaders = currentLeaders(lockName)
        return LeaderGroupState(lockName, maxLeaders, leaders.size, leaders)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private suspend fun <T> runImpl(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        val leaseHandle = acquire(lockName) ?: return null
        val delegate: SuspendExtendDelegate = EtcdSuspendLockExtendDelegate(lockClient, leaseHandle)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = ETCD_SUSPEND_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            ),
            token = leaseHandle.token,
            acquiredAtNanos = leaseHandle.acquiredAtNanos,
            slotId = leaseHandle.slotId,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
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
        val deadline = EtcdAcquisitionDeadline.fromNow(options.leaderGroupOptions.waitTime)
        val startSlot = ThreadLocalRandom.current().nextInt(maxLeaders)

        for (attempt in 0 until maxLeaders) {
            val slot = (startSlot + attempt) % maxLeaders
            val remainingSlots = maxLeaders - attempt
            val slotBudget = (deadline.remainingMillis() / remainingSlots).coerceAtLeast(50L).milliseconds
            val timeout = minOf(deadline.remainingDuration(), slotBudget)
            val handle = acquireSlot(lockName, slot, timeout)
            if (handle != null) {
                return handle
            }
        }
        log.debug { "etcd suspend leader group slot acquisition skipped. lockName=$lockName" }
        return null
    }

    private suspend fun acquireSlot(lockName: String, slot: Int, timeout: Duration): EtcdLeaseHandle? {
        var leaseId = 0L
        var lockFuture: CompletableFuture<ByteSequence>? = null
        val slotDeadline = EtcdAcquisitionDeadline.fromNow(timeout)

        try {
            val ttlSeconds = EtcdLeaseTime.ttlSeconds(options.leaderGroupOptions.leaseTime)
            leaseId = withTimeoutOrNull(slotDeadline.remainingDuration()) {
                lockClient.grantLease(ttlSeconds).await()
            } ?: run {
                log.debug { "etcd suspend group lease grant timed out. lockName=$lockName, slot=$slot" }
                return null
            }

            lockFuture = lockClient.lock(lockClient.groupSlotLockKey(lockName, slot), leaseId)
            val ownershipKey = withTimeoutOrNull(slotDeadline.remainingDuration()) { lockFuture.await() }
            if (ownershipKey == null) {
                scheduleLateCleanup(lockFuture, leaseId)
                lockFuture.cancel(true)
                revokeLease(leaseId)
                return null
            }

            return EtcdLeaseHandle(
                leaseId = leaseId,
                lockName = lockName,
                ownershipKey = ownershipKey,
                slotId = slot.toString(),
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
            log.warn(e) { "etcd suspend group slot acquisition failed. lockName=$lockName, slot=$slot" }
            return null
        }
    }

    private suspend fun releaseAfterMinLease(handle: EtcdLeaseHandle) {
        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderGroupOptions.minLeaseTime)
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

    private fun currentLeaders(lockName: String): List<LeaderLease> =
        (0 until maxLeaders).flatMap { slot ->
            runCatching {
                lockClient.ownershipKeys(lockClient.groupSlotLockKey(lockName, slot))
                    .get()
                    .map { key ->
                        LeaderLease(
                            auditLeaderId = EtcdLeaseHandle.ownershipToken(key),
                            slot = slot,
                        )
                    }
            }.getOrElse { e ->
                log.warn(e) { "etcd suspend group state query failed. lockName=$lockName, slot=$slot" }
                emptyList()
            }
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
                log.debug { "etcd suspend group unlock skipped because key is already gone." }
            } else {
                log.warn(e) { "etcd suspend group unlock failed." }
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
                log.debug { "etcd suspend group lease revoke skipped because lease is already gone. leaseId=$leaseId" }
            } else {
                log.warn(e) { "etcd suspend group lease revoke failed. leaseId=$leaseId" }
            }
        }
    }
}

suspend inline fun <T> Client.suspendRunIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? =
    EtcdSuspendLeaderGroupElector(this, options).runIfLeader(lockName) { action() }
