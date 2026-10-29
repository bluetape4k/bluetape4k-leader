package io.bluetape4k.leader

import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer

/**
 * election 획득·해제·건너뜀을 통지받는 callback 계약입니다.
 *
 */
interface LeaderElectionListener {

    /** lock의 leadership을 획득했을 때 호출됩니다. */
    fun onElected(lockName: String) = Unit

    /**
     * lock의 leadership을 획득했을 때 lease 정보와 함께 호출됩니다. 기본 구현은 기존 callback으로 위임합니다.
     * @param lockName leadership을 획득한 lock 이름입니다.
     * @param leader 획득한 lease입니다. backend가 lease 정보를 전달하지 않으면 `null`입니다.
     */
    fun onElected(lockName: String, leader: LeaderLease?) {
        onElected(lockName)
    }

    /** 보유하던 lock의 leadership을 잃었을 때 호출됩니다. */
    fun onRevoked(lockName: String) = Unit

    /** 정상적인 lock contention 또는 정책에 따라 작업을 실행하지 않았을 때 호출됩니다. */
    fun onSkipped(lockName: String) = Unit
}

/**
 * lock 획득·해제·건너뜀 이벤트의 공통 lock 식별자를 제공합니다.
 *
 */
sealed interface LeaderElectionEvent: Serializable {
    /** 이벤트가 가리키는 lock 이름입니다. */
    val lockName: String

    /**
     * lock의 leadership 획득을 나타내는 이벤트입니다.
     *
     * @property lockName leadership을 획득한 lock 이름입니다.
     * @property leaderId 이 이벤트에 연결한 audit leader ID입니다. 제공되지 않으면 `null`입니다.
     * @property leaseExpiry backend가 알린 lease 만료 절대 시각입니다. 만료 시각이 없으면 `null`입니다.
     * @property leader 획득 직후의 lease snapshot입니다. 세부 정보를 제공하지 않으면 `null`입니다.
     */
    data class Elected @JvmOverloads constructor(
        override val lockName: String,
        val leaderId: String? = null,
        val leaseExpiry: Instant? = null,
        val leader: LeaderLease? = null,
    ): LeaderElectionEvent, Serializable {
        companion object {
            private const val serialVersionUID = 2L

            /** lease snapshot에서 획득 이벤트를 만들고 leader ID와 만료 시각을 복사합니다. */
            fun fromLease(lockName: String, leader: LeaderLease?): Elected =
                Elected(
                    lockName = lockName,
                    leaderId = leader?.auditLeaderId,
                    leaseExpiry = leader?.leaseUntil,
                    leader = leader,
                )
        }
    }

    /**
     * lock의 leadership 상실을 나타내는 이벤트입니다.
     *
     * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     */
    data class Revoked(override val lockName: String): LeaderElectionEvent {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * lock을 얻지 못해 보호된 작업을 실행하지 않은 이벤트입니다.
     *
     * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     */
    data class Skipped(override val lockName: String): LeaderElectionEvent {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * lifecycle event를 Flow로 발행하고, 지정한 CoroutineScope에서 event callback을 구독하게 합니다.
 *
 */
interface LeaderElectionEventPublisher {

    /** 발행된 election lifecycle event를 전달하는 `Flow`입니다. */
    val events: Flow<LeaderElectionEvent>

    /** 모든 election event를 구독합니다. callback 예외는 로그에 남기고 무시합니다.
     * @param scope 구독 coroutine의 실행 및 취소 수명주기를 소유하는 scope입니다.
     * @param listener 각 event에 호출할 callback입니다.
     * @return 구독 job을 취소하는 handle입니다. scope가 취소되어도 구독은 종료됩니다.
     */
    fun onEvent(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onEvent", LeaderElectionEvent::class.java, listener)

    /** `Elected` event만 구독합니다. callback 예외는 로그에 남기고 무시합니다.
     * @param scope 구독 coroutine의 실행 및 취소 수명주기를 소유하는 scope입니다.
     * @param listener `Elected` event에 호출할 callback입니다.
     * @return 구독 job을 취소하는 handle입니다.
     */
    fun onElected(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Elected>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onElected", LeaderElectionEvent.Elected::class.java, listener)

    /** `Revoked` event만 구독합니다. callback 예외는 로그에 남기고 무시합니다.
     * @param scope 구독 coroutine의 실행 및 취소 수명주기를 소유하는 scope입니다.
     * @param listener `Revoked` event에 호출할 callback입니다.
     * @return 구독 job을 취소하는 handle입니다.
     */
    fun onRevoked(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Revoked>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onRevoked", LeaderElectionEvent.Revoked::class.java, listener)

    /** `Skipped` event만 구독합니다. callback 예외는 로그에 남기고 무시합니다.
     * @param scope 구독 coroutine의 실행 및 취소 수명주기를 소유하는 scope입니다.
     * @param listener `Skipped` event에 호출할 callback입니다.
     * @return 구독 job을 취소하는 handle입니다.
     */
    fun onSkipped(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Skipped>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onSkipped", LeaderElectionEvent.Skipped::class.java, listener)
}

private fun <T: LeaderElectionEvent> LeaderElectionEventPublisher.subscribeToEvents(
    scope: CoroutineScope,
    callbackName: String,
    eventType: Class<T>,
    listener: Consumer<T>,
): AutoCloseable {
    val job = events
        .onEach { event ->
            if (eventType.isInstance(event)) {
                val typedEvent = eventType.cast(event)

                runCatching {
                    listener.accept(typedEvent)
                }.onFailure { e ->
                    LeaderElectionEventPublisherCallbackLogger.log.warn(e) {
                        "LeaderElectionEventPublisher $callbackName callback failed and was ignored. " +
                                "lockName=${event.lockName}"
                    }
                }
            }
        }
        .log("Event")
        .launchIn(scope)

    return AutoCloseable { job.cancel() }
}

private object LeaderElectionEventPublisherCallbackLogger: KLogging()

/**
 * election lifecycle callback listener를 중복 없이 등록하고 제거합니다.
 *
 */
interface LeaderElectionListenerRegistry {

    /** @param listener 획득·해제·건너뜀을 통지받을 listener입니다. 같은 인스턴스는 중복 등록되지 않습니다. */
    fun addListener(listener: LeaderElectionListener): AutoCloseable

    /**
     * 등록된 listener를 제거합니다.
     * @param listener 제거할 listener 인스턴스입니다.
     * @return 이 인스턴스가 등록 목록에서 제거되면 `true`입니다.
     */
    fun removeListener(listener: LeaderElectionListener): Boolean
}

/**
 * listener 목록을 thread-safe하게 관리하고 callback 예외를 기록한 뒤 다음 listener 통지를 계속합니다.
 *
 */
open class LeaderElectionListenerSupport: LeaderElectionListenerRegistry {

    private companion object: KLogging()

    private val lock = ReentrantLock()
    private val listeners = ConcurrentLinkedQueue<LeaderElectionListener>()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable {
        lock.withLock {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
            return AutoCloseable { removeListener(listener) }
        }
    }

    override fun removeListener(listener: LeaderElectionListener): Boolean = lock.withLock {
        listeners.remove(listener)
    }

    /**
     * 등록된 listener 각각에 획득 callback을 보냅니다. callback 예외는 기록하고 다음 listener로 계속합니다.
     * @param lockName leadership을 획득한 lock 이름입니다.
     * @param leader 획득한 lease snapshot입니다. backend가 세부 정보를 제공하지 않으면 `null`입니다.
     */
    fun notifyElected(lockName: String, leader: LeaderLease? = null) {
        notify(lockName, "onElected") { it.onElected(lockName, leader) }
    }

    /**
     * 등록된 listener 각각에 leadership 상실 callback을 보냅니다. callback 예외는 기록하고 다음 listener로 계속합니다.
     * @param lockName leadership을 잃은 lock 이름입니다.
     */
    fun notifyRevoked(lockName: String) {
        notify(lockName, "onRevoked") { it.onRevoked(lockName) }
    }

    /**
     * 등록된 listener 각각에 작업 건너뜀 callback을 보냅니다. callback 예외는 기록하고 다음 listener로 계속합니다.
     * @param lockName 시도했지만 작업을 실행하지 않은 lock 이름입니다.
     */
    fun notifySkipped(lockName: String) {
        notify(lockName, "onSkipped") { it.onSkipped(lockName) }
    }

    private fun notify(
        lockName: String,
        callbackName: String,
        callback: (LeaderElectionListener) -> Unit,
    ) {
        listeners.forEach { listener ->
            runCatching {
                callback(listener)
            }.onFailure { e ->
                log.warn(e) {
                    "LeaderElectionListener $callbackName failed and was ignored. lockName=$lockName"
                }
            }
        }
    }
}
