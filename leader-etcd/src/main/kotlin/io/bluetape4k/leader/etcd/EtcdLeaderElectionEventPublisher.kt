package io.bluetape4k.leader.etcd

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.etcd.internal.EtcdKeyEncoder
import io.bluetape4k.leader.etcd.internal.EtcdLeaderPaths
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.etcd.jetcd.ByteSequence
import io.etcd.jetcd.Client
import io.etcd.jetcd.KV
import io.etcd.jetcd.Watch
import io.etcd.jetcd.options.GetOption
import io.etcd.jetcd.options.GetOption.SortOrder
import io.etcd.jetcd.options.GetOption.SortTarget
import io.etcd.jetcd.options.WatchOption
import io.etcd.jetcd.watch.WatchEvent
import io.etcd.jetcd.watch.WatchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Watch-backed etcd event publisher for leader ownership key changes.
 *
 * The supplied jetcd [Client] is caller-owned and is never closed by this publisher.
 * Closing this publisher only closes the active watch and its internally owned coroutine scope.
 */
class EtcdLeaderElectionEventPublisher @JvmOverloads constructor(
    client: Client,
    keyPrefix: String = EtcdLeaderPaths.DefaultPrefix,
    coroutineScope: CoroutineScope? = null,
    eventBufferCapacity: Int = DefaultEventBufferCapacity,
) : LeaderElectionEventPublisher, AutoCloseable {

    private val kvClient: KV = client.kvClient
    private val watchClient: Watch = client.watchClient
    private val paths = EtcdLeaderPaths(keyPrefix)
    private val ownsScope = coroutineScope == null
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val failureCount = AtomicInteger(0)
    private val watcherRef = AtomicReference<Watch.Watcher?>(null)
    private val activeOwnerKeys = ConcurrentHashMap<String, String>()
    private val eventSubject = MutableSharedFlow<LeaderElectionEvent>(
        extraBufferCapacity = eventBufferCapacity.coerceAtLeast(1),
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<LeaderElectionEvent> = eventSubject.asSharedFlow()

    init {
        startWatch()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        watcherRef.getAndSet(null)?.let { watcher ->
            runCatching { watcher.close() }
                .onFailure { e -> log.debug(e) { "etcd watch close skipped." } }
        }
        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun startWatch() {
        if (closed.get()) {
            return
        }

        val option = WatchOption.builder()
            .isPrefix(true)
            .withRequireLeader(true)
            .build()
        val watcher = watchClient.watch(
            ByteSequence.from(paths.keyPrefix, StandardCharsets.UTF_8),
            option,
            Watch.listener(::onResponse, ::onFailure, ::onCompleted),
        )
        if (closed.get()) {
            runCatching { watcher.close() }
                .onFailure { e -> log.debug(e) { "etcd watch close skipped after publisher close." } }
            return
        }
        watcherRef.getAndSet(watcher)?.let { oldWatcher ->
            runCatching { oldWatcher.close() }
                .onFailure { e -> log.debug(e) { "previous etcd watch close skipped." } }
        }
        if (closed.get() && watcherRef.compareAndSet(watcher, null)) {
            runCatching { watcher.close() }
                .onFailure { e -> log.debug(e) { "etcd watch close skipped after concurrent publisher close." } }
        }
        scope.launch {
            seedCurrentOwners()
        }
    }

    private fun onResponse(response: WatchResponse) {
        failureCount.set(0)
        val events = response.events
            .mapNotNull { event ->
                resourceFromKey(event.keyValue.key)?.let { resource -> resource to event }
            }
        scope.launch {
            events.forEach { (resource, event) ->
                if (!closed.get()) {
                    revalidateOwner(resource, event)
                }
            }
        }
    }

    private fun onFailure(error: Throwable) {
        if (closed.get()) {
            return
        }
        val failures = failureCount.incrementAndGet()
        if (failures > MaxConsecutiveFailures) {
            log.warn(error) { "etcd watch closed after repeated failures. keyPrefix=${paths.keyPrefix}" }
            close()
            return
        }

        val backoffMs = restartBackoffMs(failures)
        log.warn(error) { "etcd watch failed. Restarting in ${backoffMs}ms. keyPrefix=${paths.keyPrefix}" }
        scope.launch {
            delay(backoffMs)
            startWatch()
        }
    }

    private fun onCompleted() {
        if (closed.get()) {
            return
        }
        onFailure(IllegalStateException("etcd watch completed"))
    }

    private suspend fun seedCurrentOwners() {
        try {
            val option = GetOption.builder()
                .isPrefix(true)
                .build()
            val resources = kvClient.get(ByteSequence.from(paths.keyPrefix, StandardCharsets.UTF_8), option)
                .await()
                .kvs
                .mapNotNull { kv -> resourceFromKey(kv.key) }
                .distinctBy { resource -> resource.id }

            resources.forEach { resource ->
                currentOwnerKey(resource)?.let { ownerKey ->
                    activeOwnerKeys.putIfAbsent(resource.id, ownerKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "etcd watch initial owner seed failed. keyPrefix=${paths.keyPrefix}" }
        }
    }

    private suspend fun revalidateOwner(resource: WatchedResource, event: WatchEvent) {
        try {
            val eventOwnerKey = event.keyValue.key.toString(StandardCharsets.UTF_8)
            val currentOwnerKey = currentOwnerKey(resource)
            when (event.eventType) {
                WatchEvent.EventType.PUT -> handlePut(resource, eventOwnerKey, currentOwnerKey)
                WatchEvent.EventType.DELETE -> handleDelete(resource, eventOwnerKey, currentOwnerKey)
                WatchEvent.EventType.UNRECOGNIZED -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "etcd watch state revalidation failed. resource=${resource.id}" }
        }
    }

    private fun handlePut(resource: WatchedResource, eventOwnerKey: String, currentOwnerKey: String?) {
        val previousOwnerKey = activeOwnerKeys[resource.id]
        val eventIsCurrentOwner = currentOwnerKey == eventOwnerKey
        val eventWasShortLivedOwner = previousOwnerKey == null && currentOwnerKey == null
        if (!eventIsCurrentOwner && !eventWasShortLivedOwner) {
            return
        }
        val replacedOwnerKey = activeOwnerKeys.put(resource.id, eventOwnerKey)
        if (replacedOwnerKey != eventOwnerKey) {
            eventSubject.tryEmit(LeaderElectionEvent.Elected(resource.lockName))
        }
    }

    private fun handleDelete(resource: WatchedResource, eventOwnerKey: String, currentOwnerKey: String?) {
        val previousOwnerKey = activeOwnerKeys[resource.id]
        if (previousOwnerKey == eventOwnerKey) {
            activeOwnerKeys.remove(resource.id, eventOwnerKey)
            eventSubject.tryEmit(LeaderElectionEvent.Revoked(resource.lockName))
        }

        if (currentOwnerKey != null && currentOwnerKey != eventOwnerKey) {
            val replacedOwnerKey = activeOwnerKeys.put(resource.id, currentOwnerKey)
            if (replacedOwnerKey != currentOwnerKey) {
                eventSubject.tryEmit(LeaderElectionEvent.Elected(resource.lockName))
            }
        }
    }

    private suspend fun currentOwnerKey(resource: WatchedResource): String? {
        val option = GetOption.builder()
            .isPrefix(true)
            .withSortField(SortTarget.CREATE)
            .withSortOrder(SortOrder.ASCEND)
            .withLimit(1)
            .build()
        return kvClient.get(resource.lockKey, option)
            .await()
            .kvs
            .firstOrNull()
            ?.key
            ?.toString(StandardCharsets.UTF_8)
    }

    private fun resourceFromKey(key: ByteSequence): WatchedResource? {
        val path = key.toString(StandardCharsets.UTF_8)
        val singlePrefix = "${paths.keyPrefix}/single/"
        if (path.startsWith(singlePrefix)) {
            val encodedLockName = firstSegment(path.removePrefix(singlePrefix)) ?: return null
            val lockName = decodeSegment(encodedLockName) ?: return null
            val lockKey = ByteSequence.from("$singlePrefix$encodedLockName", StandardCharsets.UTF_8)
            return WatchedResource(
                id = "single:$encodedLockName",
                lockName = lockName,
                lockKey = lockKey,
            )
        }

        val groupPrefix = "${paths.keyPrefix}/group/"
        if (path.startsWith(groupPrefix)) {
            val afterGroupPrefix = path.removePrefix(groupPrefix)
            val encodedLockName = firstSegment(afterGroupPrefix) ?: return null
            val afterLockName = afterGroupPrefix.substringAfter('/', missingDelimiterValue = "")
            val slotSegment = firstSegment(afterLockName)
                ?.takeIf { it.startsWith(GroupSlotPrefix) }
                ?: return null
            val lockName = decodeSegment(encodedLockName) ?: return null
            val lockKey = ByteSequence.from("$groupPrefix$encodedLockName/$slotSegment", StandardCharsets.UTF_8)
            return WatchedResource(
                id = "group:$encodedLockName:$slotSegment",
                lockName = lockName,
                lockKey = lockKey,
            )
        }

        return null
    }

    private fun firstSegment(value: String): String? =
        value.substringBefore('/').takeIf { it.isNotBlank() }

    private fun decodeSegment(segment: String): String? =
        runCatching { EtcdKeyEncoder.decodeSegment(segment) }
            .onFailure { e -> log.debug(e) { "etcd watch ignored an undecodable ownership key segment." } }
            .getOrNull()

    private fun restartBackoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 5)
        return (InitialRestartBackoffMs shl shift).coerceAtMost(MaxRestartBackoffMs)
    }

    private data class WatchedResource(
        val id: String,
        val lockName: String,
        val lockKey: ByteSequence,
    )

    companion object: KLogging() {
        const val DefaultEventBufferCapacity: Int = 64
        private const val GroupSlotPrefix: String = "slot-"
        private const val InitialRestartBackoffMs: Long = 200
        private const val MaxRestartBackoffMs: Long = 5_000
        private const val MaxConsecutiveFailures: Int = 10
    }
}
