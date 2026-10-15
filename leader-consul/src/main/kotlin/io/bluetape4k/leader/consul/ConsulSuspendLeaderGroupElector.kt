package io.bluetape4k.leader.consul

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.consul.internal.ConsulLeaseHandle
import io.bluetape4k.leader.consul.internal.ConsulLockClient
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.ConsulSessionId
import io.bluetape4k.leader.consul.internal.ConsulSessionTtl
import io.bluetape4k.leader.consul.internal.ConsulSuspendLockExtendDelegate
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.leader.consul.internal.getWithinRequestTimeout
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * `ConsulSuspendLeaderGroupElector`는 Consul KV slot key와 session을 이용해 여러 leader의 suspend 실행을 관리합니다.
 *
 * @property lockClient suspend session 생성·그룹 KV acquire/release·renew/read/destroy를 Consul API에 위임하는 클라이언트입니다.
 * @property options maxLeaders, lease 범위(10초~24시간), KV prefix, session prefix, lock delay를 포함한 그룹 설정입니다.
 */
// Suspend group elector는 상태 조회, lease lifecycle, diagnostics 계약을 함께 구현합니다.
@Suppress("TooManyFunctions")
class ConsulSuspendLeaderGroupElector private constructor(
    private val lockClient: ConsulLockClient,
    val options: ConsulLeaderGroupElectionOptions,
): SuspendLeaderGroupElector,
   LeaderBackendDiagnosticsProvider by ConsulLeaderBackendDiagnostics {

    constructor(
        endpoint: ConsulEndpoint,
        options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    ): this(JavaHttpConsulLockClient(endpoint, options.keyPrefix), options)

    companion object: KLoggingChannel() {
        internal const val CONSUL_SUSPEND_GROUP_FACTORY_BEAN_NAME = "consul-suspend-leader-group-elector"

        internal fun create(
            lockClient: ConsulLockClient,
            options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
        ): ConsulSuspendLeaderGroupElector =
            ConsulSuspendLeaderGroupElector(lockClient, options)
    }

    override val maxLeaders: Int = options.maxLeaders

    override fun activeCount(lockName: String): Int =
        currentLeaders(lockName).size

    override fun availableSlots(lockName: String): Int =
        maxLeaders - activeCount(lockName)

    // State snapshots keep the synchronous interface contract and can block up to the Consul request timeout.
    override fun state(lockName: String): LeaderGroupState {
        val leaders = currentLeaders(lockName)
        return LeaderGroupState(lockName, maxLeaders, leaders.size, leaders)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runWithSlot(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runWithSlot(slot.lockName, auditLeaderId = slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(
        slot: LeaderSlot,
        action: suspend () -> T,
    ): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runIfLeader(slot) {
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

    private suspend fun <T> runWithSlot(
        lockName: String,
        auditLeaderId: String?,
        action: suspend () -> T,
    ): T? {
        currentCoroutineContext().ensureActive()
        val handle = acquire(lockName, auditLeaderId) ?: return null
        val delegate: SuspendExtendDelegate = ConsulSuspendLockExtendDelegate(lockClient, handle)
        val lockHandle = LeaderLockHandle.real(
            identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = CONSUL_SUSPEND_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            ),
            token = handle.ownerToken,
            acquiredAtNanos = handle.acquiredAtNanos,
            slotId = handle.slotId,
            extendDelegate = delegate,
            auditLeaderId = handle.auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            // Group auto-extension is disabled; explicit LockExtender renews the Consul session.
            enabled = false,
            leaseTime = options.leaderGroupOptions.leaseTime,
            delegate = delegate,
            classifier = ConsulLeaderElector.ERROR_CLASSIFIER,
        )

        try {
            return withContext(AopScopeAccess.createLockHandleElement(lockHandle)) {
                action()
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    delayBeforeRelease(handle)
                } finally {
                    LeaderLeaseAutoExtender.closeSuspend(watchdog)
                    release(handle)
                }
            }
        }
    }

    private suspend fun acquire(lockName: String, auditLeaderId: String?): ConsulLeaseHandle? {
        val electedAt = Instant.now()
        val leaseUntil = electedAt.plusMillis(options.leaderGroupOptions.leaseTime.inWholeMilliseconds)
        val ownerToken = Base58.randomString(12)
        val payload = ConsulOwnerPayload(
            ownerToken = ownerToken,
            auditLeaderId = auditLeaderId ?: options.leaderGroupOptions.nodeId,
            nodeId = options.leaderGroupOptions.nodeId,
            electedAt = electedAt,
            leaseUntil = leaseUntil,
        )
        val sessionId = try {
            lockClient.createSession(
                name = "${options.sessionNamePrefix}-${options.leaderGroupOptions.nodeId}",
                ttl = options.leaderGroupOptions.leaseTime,
                lockDelay = options.lockDelay,
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LeaderElectionException("Failed to create Consul suspend group session. lockName=$lockName", e)
        }

        try {
            val acquired = acquireWithinWaitTime(lockName, sessionId, payload)
            if (acquired == null) {
                destroySession(sessionId)
                log.debug { "Consul suspend leader group slot acquisition skipped by contention. lockName=$lockName" }
                return null
            }
            val (slot, key) = acquired
            return ConsulLeaseHandle(
                lockName = lockName,
                key = key,
                sessionId = sessionId,
                ownerToken = ownerToken,
                auditLeaderId = payload.auditLeaderId,
                nodeId = payload.nodeId,
                electedAt = electedAt,
                leaseUntil = leaseUntil,
                slotId = slot.toString(),
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                destroySession(sessionId)
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                destroySession(sessionId)
            }
            throw LeaderElectionException("Failed to acquire Consul suspend group slot. lockName=$lockName", e)
        }
    }

    private suspend fun acquireWithinWaitTime(
        lockName: String,
        sessionId: ConsulSessionId,
        ownerPayload: ConsulOwnerPayload,
    ): Pair<Int, String>? {
        val timeoutNanos = options.leaderGroupOptions.waitTime.inWholeNanoseconds
        val deadline = System.nanoTime() + timeoutNanos
        val renewDelayNanos = ConsulSessionTtl.renewDelay(options.leaderGroupOptions.leaseTime).inWholeNanoseconds
        var lastRenewNanos = System.nanoTime()
        val payloadJson = ownerPayload.toJson()
        val slotsPerAttempt = consulGroupSlotProbeCount(maxLeaders)

        do {
            currentCoroutineContext().ensureActive()
            val startSlot = java.util.concurrent.ThreadLocalRandom.current().nextInt(maxLeaders)
            for (attempt in 0 until slotsPerAttempt) {
                val slot = (startSlot + attempt) % maxLeaders
                val key = lockClient.groupLockKey(lockName, slot)
                if (lockClient.acquire(key, sessionId, payloadJson).await()) {
                    return slot to key
                }
            }
            if (timeoutNanos == 0L || System.nanoTime() >= deadline) {
                return null
            }
            val now = System.nanoTime()
            if (now - lastRenewNanos >= renewDelayNanos) {
                lockClient.renewSession(sessionId).await()
                lastRenewNanos = now
            }
            delay(consulGroupAcquireDelayMillis(deadline).milliseconds)
        } while (true)
    }

    private suspend fun delayBeforeRelease(handle: ConsulLeaseHandle) {
        val remaining = remainingMinLeaseTime(handle.acquiredAtNanos, options.leaderGroupOptions.minLeaseTime)
        if (remaining.isPositive()) {
            delay(remaining)
        }
    }

    private suspend fun release(handle: ConsulLeaseHandle) {
        if (!handle.markReleased()) {
            return
        }

        try {
            lockClient.release(handle.key, handle.sessionId).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to release Consul suspend group slot. lockName=${handle.lockName}" }
        }
        destroySession(handle.sessionId)
    }

    private suspend fun destroySession(sessionId: ConsulSessionId) {
        try {
            lockClient.destroySession(sessionId).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Failed to destroy Consul suspend group session. sessionId=${sessionId.value}" }
        }
    }

    private fun currentLeaders(lockName: String): List<LeaderLease> =
        List(maxLeaders) { slot ->
            runCatching {
                val entry = lockClient.read(lockClient.groupLockKey(lockName, slot)).getWithinRequestTimeout(lockClient)
                    ?: return@runCatching null
                if (entry.sessionId == null) {
                    return@runCatching null
                }
                val lease = entry.value?.let { ConsulOwnerPayload.fromJson(it)?.toLeaderLease(slot) }
                if (lease == null) {
                    log.warn {
                        "Consul suspend group state ignored because owner payload is missing or invalid. " +
                                "lockName=$lockName, slot=$slot, sessionId=${entry.sessionId.value}"
                    }
                }
                lease
            }.getOrElse { e ->
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                log.warn(e) { "Consul suspend group state query failed. lockName=$lockName, slot=$slot" }
                null
            }
        }.filterNotNull()
}

/**
 * [this] Consul endpoint에서 그룹 [lockName]의 슬롯을 suspend 방식으로 획득했을 때만 [action]을 실행합니다.
 * 슬롯을 얻지 못하면 null을 반환하고 취소 뒤에도 session과 ownership을 정리합니다.
 */
suspend fun <T> ConsulEndpoint.suspendRunIfLeaderGroup(
    lockName: String,
    options: ConsulLeaderGroupElectionOptions = ConsulLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = ConsulSuspendLeaderGroupElector(this, options).runIfLeader(lockName, action)
