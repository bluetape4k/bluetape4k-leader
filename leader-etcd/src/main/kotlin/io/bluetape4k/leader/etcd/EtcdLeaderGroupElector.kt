package io.bluetape4k.leader.etcd

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * etcd v3 multi-leader election backed by one jetcd Lock lease per group slot.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by this elector.
 */
class EtcdLeaderGroupElector private constructor(
    private val lockClient: EtcdLockClient,
    val options: EtcdLeaderGroupElectionOptions,
) : LeaderGroupElector {

    companion object: KLogging() {
        internal const val ETCD_GROUP_FACTORY_BEAN_NAME = "etcd-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(EtcdBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            client: Client,
            options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
        ): EtcdLeaderGroupElector =
            EtcdLeaderGroupElector(JetcdEtcdLockClient(client, options.keyPrefix), options)

        internal fun create(
            lockClient: EtcdLockClient,
            options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
        ): EtcdLeaderGroupElector =
            EtcdLeaderGroupElector(lockClient, options)
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

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
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

    private fun <T> runImpl(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        val leaseHandle = acquire(lockName) ?: return null
        val delegate = EtcdLockExtendDelegate(lockClient, leaseHandle)
        val handle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = ETCD_GROUP_FACTORY_BEAN_NAME,
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

        return try {
            AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            watchdog.close()
            releaseAfterMinLease(leaseHandle)
        }
    }

    private fun acquire(lockName: String): EtcdLeaseHandle? {
        val deadline = EtcdAcquisitionDeadline.fromNow(options.leaderGroupOptions.waitTime)
        val startSlot = ThreadLocalRandom.current().nextInt(maxLeaders)

        for (attempt in 0 until maxLeaders) {
            val slot = (startSlot + attempt) % maxLeaders
            val remainingSlots = maxLeaders - attempt
            val slotBudgetMs = (deadline.remainingMillis() / remainingSlots).coerceAtLeast(50L)
            val timeoutMs = minOf(deadline.remainingMillis(), slotBudgetMs)
            val handle = acquireSlot(lockName, slot, timeoutMs)
            if (handle != null) {
                return handle
            }
        }
        log.debug { "etcd leader group slot acquisition skipped. lockName=$lockName" }
        return null
    }

    private fun acquireSlot(lockName: String, slot: Int, timeoutMs: Long): EtcdLeaseHandle? {
        val slotDeadline = EtcdAcquisitionDeadline.fromNow(timeoutMs.milliseconds)
        val ttlSeconds = EtcdLeaseTime.ttlSeconds(options.leaderGroupOptions.leaseTime)
        val leaseId = try {
            lockClient.grantLease(ttlSeconds).get(slotDeadline.remainingMillis(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: TimeoutException) {
            log.debug { "etcd group lease grant timed out. lockName=$lockName, slot=$slot" }
            return null
        } catch (e: Exception) {
            log.warn(e) { "etcd group lease grant failed. lockName=$lockName, slot=$slot" }
            return null
        }

        val lockKey = lockClient.groupSlotLockKey(lockName, slot)
        val lockFuture = lockClient.lock(lockKey, leaseId)
        val ownershipKey = try {
            lockFuture.get(slotDeadline.remainingMillis(), TimeUnit.MILLISECONDS)
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
            return null
        } catch (e: Exception) {
            revokeLease(leaseId)
            log.warn(e) { "etcd group slot acquisition failed. lockName=$lockName, slot=$slot" }
            return null
        }

        return EtcdLeaseHandle(
            leaseId = leaseId,
            lockName = lockName,
            ownershipKey = ownershipKey,
            slotId = slot.toString(),
        )
    }

    private fun releaseAfterMinLease(handle: EtcdLeaseHandle) {
        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderGroupOptions.minLeaseTime)
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

    private fun currentLeaders(lockName: String): List<LeaderLease> =
        (0 until maxLeaders).flatMap { slot ->
            runCatching {
                lockClient.ownershipKeys(lockClient.groupSlotLockKey(lockName, slot))
                    .get(10, TimeUnit.SECONDS)
                    .map { key ->
                        LeaderLease(
                            auditLeaderId = EtcdLeaseHandle.ownershipToken(key),
                            slot = slot,
                        )
                    }
            }.getOrElse { e ->
                log.warn(e) { "etcd group state query failed. lockName=$lockName, slot=$slot" }
                emptyList()
            }
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
                    log.debug { "etcd group unlock skipped because key is already gone." }
                } else {
                    log.warn(e) { "etcd group unlock failed." }
                }
            }
    }

    private fun revokeLease(leaseId: Long) {
        runCatching { lockClient.revokeLease(leaseId).get(10, TimeUnit.SECONDS) }
            .onFailure { e ->
                if (EtcdBackendErrorClassifier.isExpectedCleanup(e)) {
                    log.debug { "etcd group lease revoke skipped because lease is already gone. leaseId=$leaseId" }
                } else {
                    log.warn(e) { "etcd group lease revoke failed. leaseId=$leaseId" }
                }
            }
    }
}

inline fun <T> Client.runIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? =
    EtcdLeaderGroupElector(this, options).runIfLeader(lockName) { action() }

fun <T> Client.runAsyncIfLeaderGroup(
    lockName: String,
    options: EtcdLeaderGroupElectionOptions = EtcdLeaderGroupElectionOptions.Default,
    executor: Executor = VirtualThreadExecutor,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> =
    EtcdLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)
