package io.bluetape4k.leader.ktor.stream

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.ktor.LeaderElectionCloseAwaiter
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ktor event stream이 hub 내부에서 사용하는 event/control item입니다.
 *
 * 이 타입은 transport adapter가 SSE와 WebSocket framing을 독립적으로 적용할 수 있도록
 * internal로 유지합니다. `Event`만 application sequence를 가지며 `Control`은 replay
 * 상태를 설명하는 bounded control frame입니다.
 */
internal sealed interface LeaderStreamItem {
    val kind: Kind

    enum class Kind {
        EVENT,
        HEARTBEAT,
        REPLAY_GAP,
    }

    data class Event(
        val sequence: Long,
        val event: LeaderElectionEvent,
    ) : LeaderStreamItem {
        override val kind: Kind = Kind.EVENT
    }

    data class Control(
        val control: Kind,
        val from: Long? = null,
        val to: Long? = null,
    ) : LeaderStreamItem {
        init {
            require(control != Kind.EVENT) { "event control item cannot use EVENT kind" }
            if (control == Kind.REPLAY_GAP) {
                require(from != null && to != null) {
                    "REPLAY_GAP control item requires from and to"
                }
                require(from >= 0 && to >= from) {
                    "REPLAY_GAP control item range must be non-negative and ordered"
                }
            }
        }

        override val kind: Kind
            get() = control
    }
}

/**
 * Core `LeaderElectionEventPublisher`의 hot event를 bounded replay/live stream으로
 * 변환하는 application-owned hub입니다.
 *
 * sequence 증가, ring eviction, subscriber 등록, replay enqueue와 live fan-out은 하나의
 * `Mutex` 경계에서 수행됩니다. 따라서 subscriber가 등록된 뒤 첫 live event가 도착해도
 * replay와 live 사이에 gap이나 duplicate가 생기지 않습니다. hub는 publisher 또는
 * caller-owned `CoroutineScope`를 닫지 않으며, `close()`에서 자신이 시작한 collector와
 * connection channel만 닫습니다.
 */
@Suppress("TooManyFunctions")
internal class LeaderEventStreamHub(
    private val publisher: LeaderElectionEventPublisher,
    private val capacity: Int,
    scope: CoroutineScope,
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    private val allLocksEnabled: Boolean = false,
) : AutoCloseable, LeaderElectionCloseAwaiter {

    internal constructor(
        publisher: LeaderElectionEventPublisher,
        config: LeaderEventStreamConfig,
        scope: CoroutineScope,
    ) : this(
        publisher = publisher,
        capacity = config.eventStreamReplayCapacity,
        scope = scope,
        maxConnections = config.eventStreamMaxConnections,
        allLocksEnabled = config.eventStreamAllLocksEnabled,
    )

    init {
        require(capacity in MIN_CAPACITY..MAX_CAPACITY) {
            "eventStreamReplayCapacity must be between $MIN_CAPACITY and $MAX_CAPACITY"
        }
        require(maxConnections in MIN_CONNECTIONS..MAX_CONNECTIONS) {
            "eventStreamMaxConnections must be between $MIN_CONNECTIONS and $MAX_CONNECTIONS"
        }
    }

    private val mutex = Mutex()
    private val ring = ArrayDeque<SequencedLeaderEvent>(capacity)
    private val subscribers = LinkedHashSet<Subscriber>()
    private val subscriberWaiters = mutableListOf<SubscriberWaiter>()
    private val started = CompletableDeferred<Unit>()
    private val closedCompletion = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val droppedItems = AtomicLong()
    private val nextSequence = AtomicLong()

    /*
     * Closing is a non-suspending AutoCloseable operation, while the state lock is a
     * coroutine Mutex. Keep a small hub-owned scope for the close transaction so close()
     * remains non-blocking and still works when the application scope is already cancelling.
     */
    private val closeScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) +
            kotlinx.coroutines.SupervisorJob(),
    )

    @Suppress("TooGenericExceptionCaught")
    private val collectorJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        started.complete(Unit)
        try {
            publisher.events.collect { event ->
                append(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LeaderEventStreamHubLogger.log.warn(e) {
                "Leader event stream publisher collector failed; active connections will close."
            }
        } finally {
            if (closed.compareAndSet(false, true)) {
                closeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    closeInternal()
                }
            }
        }
    }

    /** 현재 hub collector가 publisher를 구독하기 시작할 때까지 기다립니다. */
    internal suspend fun awaitStarted() {
        started.await()
    }

    /**
     * 지정한 subscriber 수 이상이 등록될 때까지 기다리는 deterministic test barrier입니다.
     * 운영 adapter는 이 내부 helper를 사용하지 않습니다.
     */
    internal suspend fun awaitSubscriberCount(expected: Int) {
        require(expected >= 0) { "expected subscriber count must be non-negative" }
        val waiter = mutex.withLock {
            if (subscribers.size >= expected) {
                null
            } else {
                SubscriberWaiter(expected).also { subscriberWaiters += it }
            }
        }
        waiter?.completion?.await()
    }

    /** 현재 등록된 connection 수를 반환합니다. */
    internal suspend fun subscriberCount(): Int = mutex.withLock { subscribers.size }

    /** bounded channel overflow로 폐기된 item의 누적 수입니다. */
    internal val droppedItemCount: Long
        get() = droppedItems.get()

    /**
     * 현재 ring에서 cursor 뒤에 남아 있는 replay item을 반환합니다.
     *
     * `lockName`이 null이면 이 read-only snapshot은 모든 lock을 대상으로 합니다. 실제
     * connection은 `subscribe`의 all-lock policy를 거쳐야 합니다. capacity가 0이면
     * cursor가 유효해도 replay와 `REPLAY_GAP`을 만들지 않는 live-only 모드입니다.
     */
    internal suspend fun replay(
        afterSequence: Long?,
        lockName: String? = null,
    ): List<LeaderStreamItem> = mutex.withLock {
        validateCursor(afterSequence)
        if (lockName != null) validateLockName(lockName)
        replayLocked(afterSequence, lockName)
    }

    /**
     * connection을 등록하고 replay를 channel에 enqueue한 뒤 live channel을 반환합니다.
     * registration과 replay enqueue는 하나의 mutex 경계에서 이루어져 atomic handoff를
     * 보장합니다. caller는 사용이 끝난 뒤 반드시 `releaseConnection`을 호출해야 합니다.
     */
    @Suppress("ThrowsCount")
    internal suspend fun acquireConnection(
        lockName: String?,
        afterSequence: Long?,
    ): LeaderEventStreamConnection {
        val normalizedLockName = normalizeFilter(lockName)
        validateCursor(afterSequence)

        if (closed.get()) throw LeaderEventStreamClosedException()

        return mutex.withLock {
            if (closed.get()) throw LeaderEventStreamClosedException()
            if (subscribers.size >= maxConnections) {
                throw LeaderEventStreamConnectionLimitException(maxConnections)
            }

            val subscriber = Subscriber(
                lockName = normalizedLockName,
                bufferCapacity = subscriberBufferCapacity,
                droppedItems = droppedItems,
            )
            val replayItems = replayLocked(afterSequence, normalizedLockName)
            subscribers += subscriber
            replayItems.forEach(subscriber::trySend)
            completeSubscriberWaitersLocked()
            LeaderEventStreamConnection(subscriber)
        }
    }

    /** connection admission을 해제하고 그 channel을 idempotent하게 닫습니다. */
    internal suspend fun releaseConnection(connection: LeaderEventStreamConnection) {
        if (!connection.releaseOnce()) return
        mutex.withLock {
            if (subscribers.remove(connection.subscriber)) {
                connection.subscriber.close()
                completeSubscriberWaitersLocked()
            } else {
                connection.subscriber.close()
            }
        }
    }

    /**
     * transport-neutral Flow facade입니다. collection 시작 시 admission이 이루어지며,
     * collector cancellation/error와 정상 완료 모두에서 connection permit을 반환합니다.
     */
    internal fun subscribe(
        lockName: String?,
        afterSequence: Long?,
    ): Flow<LeaderStreamItem> = flow {
        val connection = acquireConnection(lockName, afterSequence)
        try {
            for (item in connection.channel) emit(item)
        } finally {
            releaseConnection(connection)
        }
    }

    /** hub collector와 모든 connection channel을 idempotent하게 닫습니다. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            closeInternal()
        }
    }

    /** close transaction이 완료될 때까지 기다리는 내부 lifecycle barrier입니다. */
    override suspend fun awaitClosed() {
        closedCompletion.await()
    }

    private suspend fun append(event: LeaderElectionEvent) {
        mutex.withLock {
            if (closed.get()) return

            val currentSequence = nextSequence.get()
            check(currentSequence < Long.MAX_VALUE) {
                "Leader event stream sequence exhausted"
            }
            val sequence = nextSequence.incrementAndGet()
            if (capacity > 0) {
                if (ring.size == capacity) ring.removeFirst()
                ring.addLast(SequencedLeaderEvent(sequence, event))
            }

            if (subscribers.isNotEmpty()) {
                val item = LeaderStreamItem.Event(sequence, event)
                subscribers.forEach { subscriber ->
                    if (subscriber.matches(event)) subscriber.trySend(item)
                }
            }
        }
    }

    @Suppress("ReturnCount")
    private fun replayLocked(
        afterSequence: Long?,
        lockName: String?,
    ): List<LeaderStreamItem> {
        if (capacity == 0 || ring.isEmpty()) return emptyList()

        val first = ring.first().sequence
        val last = ring.last().sequence
        if (afterSequence != null && afterSequence >= last) return emptyList()

        val result = ArrayList<LeaderStreamItem>(ring.size + 1)
        if (afterSequence != null && afterSequence < first - 1) {
            result += LeaderStreamItem.Control(
                control = LeaderStreamItem.Kind.REPLAY_GAP,
                from = afterSequence + 1,
                to = first - 1,
            )
        }
        ring.asSequence()
            .filter { it.sequence > (afterSequence ?: Long.MIN_VALUE) }
            .filter { lockName == null || it.event.lockName == lockName }
            .mapTo(result) { LeaderStreamItem.Event(it.sequence, it.event) }
        return result
    }

    private fun normalizeFilter(lockName: String?): String? {
        if (lockName == null) {
            require(allLocksEnabled) {
                "lockName is required unless all-lock event stream is enabled"
            }
            return null
        }
        validateLockName(lockName)
        return lockName
    }

    private fun validateCursor(afterSequence: Long?) {
        require(afterSequence == null || afterSequence >= 0) {
            "event stream cursor must be non-negative"
        }
    }

    private suspend fun closeInternal() {
        mutex.withLock {
            val current = subscribers.toList()
            subscribers.clear()
            subscriberWaiters.forEach { it.completion.cancel() }
            subscriberWaiters.clear()
            current.forEach { it.close() }
            current
        }
        collectorJob.cancelAndJoin()
        if (!closedCompletion.isCompleted) closedCompletion.complete(Unit)
        closeScope.cancel()
    }

    private fun completeSubscriberWaitersLocked() {
        val satisfied = subscriberWaiters.filter { subscribers.size >= it.expected }
        satisfied.forEach { it.completion.complete(Unit) }
        subscriberWaiters.removeAll(satisfied.toSet())
    }

    /** Replay gap과 등록 직후 첫 live event가 handoff에서 보존되도록 여유를 둡니다. */
    private val subscriberBufferCapacity: Int
        get() = if (capacity == 0) 1 else capacity + HANDOFF_RESERVE

    /** A channel and its once-only admission release state. */
    internal class LeaderEventStreamConnection internal constructor(
        internal val subscriber: Subscriber,
    ) {
        private val released = AtomicBoolean(false)

        internal val channel: ReceiveChannel<LeaderStreamItem>
            get() = subscriber.receiveChannel()

        internal fun releaseOnce(): Boolean = released.compareAndSet(false, true)
    }

    internal class Subscriber(
        private val lockName: String?,
        bufferCapacity: Int,
        private val droppedItems: AtomicLong,
    ) {
        private val channel = Channel<LeaderStreamItem>(
            capacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { droppedItems.incrementAndGet() },
        )

        fun matches(event: LeaderElectionEvent): Boolean =
            lockName == null || event.lockName == lockName

        fun trySend(item: LeaderStreamItem) {
            val result = channel.trySend(item)
            if (result.isFailure) {
                // Closed/cancelled subscribers are removed by releaseConnection. A concurrent
                // close can still cross this call; count it as a bounded delivery drop.
                droppedItems.incrementAndGet()
            }
        }

        fun close() {
            channel.close()
        }

        fun receiveChannel(): ReceiveChannel<LeaderStreamItem> = channel
    }

    private data class SequencedLeaderEvent(
        val sequence: Long,
        val event: LeaderElectionEvent,
    )

    private class SubscriberWaiter(
        val expected: Int,
    ) {
        val completion = CompletableDeferred<Unit>()
    }

    companion object {
        const val MIN_CAPACITY: Int = 0
        const val MAX_CAPACITY: Int = 1024
        const val MIN_CONNECTIONS: Int = 1
        const val MAX_CONNECTIONS: Int = 1024
        const val DEFAULT_MAX_CONNECTIONS: Int = 128
        private const val HANDOFF_RESERVE: Int = 2
    }
}

/** connection admission이 상한에 도달했음을 나타내는 내부 예외입니다. */
internal class LeaderEventStreamConnectionLimitException(
    val limit: Int,
) : IllegalStateException("event stream connection limit reached: $limit")

/** 이미 닫힌 event stream hub에 연결을 시도했음을 나타내는 내부 예외입니다. */
internal class LeaderEventStreamClosedException : IllegalStateException("event stream hub is closed")

/** non-negative decimal cursor를 하나의 parser로 검증합니다. */
internal fun parseLeaderEventStreamCursor(raw: String?): Long? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    require(value.all(Char::isDigit)) {
        "event stream cursor must be a non-negative decimal value"
    }
    return value.toLongOrNull()
        ?: throw IllegalArgumentException("event stream cursor is outside the supported range")
}

private object LeaderEventStreamHubLogger : KLogging()
