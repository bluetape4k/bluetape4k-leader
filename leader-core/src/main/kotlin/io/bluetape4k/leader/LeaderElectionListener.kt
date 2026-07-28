package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * `LeaderElectionListener`는 leader election event를 관찰하거나 전달하는 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderElectionListener {

    /**
     * `onElected` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onElected(lockName: String) = Unit

    /**
     * `onElected` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param leader 현재 lock을 점유한 leader lease입니다. 비어 있으면 leadership이 없는 상태입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onElected(lockName: String, leader: LeaderLease?) {
        onElected(lockName)
    }

    /**
     * `onRevoked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onRevoked(lockName: String) = Unit

    /**
     * `onSkipped` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onSkipped(lockName: String) = Unit
}

/**
 * `LeaderElectionEvent` 선언은 leader election 계약에서 사용되는 interface입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
sealed interface LeaderElectionEvent : Serializable {
    /**
     * `lockName` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val lockName: String

    /**
     * `Elected` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @property leaderId audit에 기록할 leader identity입니다.
     * @property leaseExpiry `leaseExpiry` 호출 또는 상태 계산에 필요한 값입니다.
     * @property leader 현재 lock을 점유한 leader lease입니다. 비어 있으면 leadership이 없는 상태입니다.
     */
    data class Elected @JvmOverloads constructor(
        override val lockName: String,
        val leaderId: String? = null,
        val leaseExpiry: Instant? = null,
        val leader: LeaderLease? = null,
    ) : LeaderElectionEvent, Serializable {
        companion object {
            private const val serialVersionUID = 2L

            /**
             * `fromLease` 호출은 leader election 계약의 일부 동작을 수행합니다.
             *
             * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
             * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
             * @param leader 현재 lock을 점유한 leader lease입니다. 비어 있으면 leadership이 없는 상태입니다.
             * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
             */
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
     * `Revoked` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     */
    data class Revoked(override val lockName: String) : LeaderElectionEvent {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `Skipped` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     */
    data class Skipped(override val lockName: String) : LeaderElectionEvent {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * `LeaderElectionEventPublisher` 선언은 leader election 계약에서 사용되는 interface입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderElectionEventPublisher {

    /**
     * `events` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val events: Flow<LeaderElectionEvent>

    /**
     * `onEvent` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param scope `scope` 호출 또는 상태 계산에 필요한 값입니다.
     * @param listener `listener` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onEvent(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onEvent", LeaderElectionEvent::class.java, listener)

    /**
     * `onElected` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param scope `scope` 호출 또는 상태 계산에 필요한 값입니다.
     * @param listener `listener` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onElected(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Elected>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onElected", LeaderElectionEvent.Elected::class.java, listener)

    /**
     * `onRevoked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param scope `scope` 호출 또는 상태 계산에 필요한 값입니다.
     * @param listener `listener` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onRevoked(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Revoked>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onRevoked", LeaderElectionEvent.Revoked::class.java, listener)

    /**
     * `onSkipped` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param scope `scope` 호출 또는 상태 계산에 필요한 값입니다.
     * @param listener `listener` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun onSkipped(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Skipped>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onSkipped", LeaderElectionEvent.Skipped::class.java, listener)
}

private fun <T : LeaderElectionEvent> LeaderElectionEventPublisher.subscribeToEvents(
    scope: CoroutineScope,
    callbackName: String,
    eventType: Class<T>,
    listener: Consumer<T>,
): AutoCloseable {
    val job = events
        .onEach { event ->
            if (eventType.isInstance(event)) {
                val typedEvent = eventType.cast(event)
                runCatching { listener.accept(typedEvent) }
                    .onFailure { e ->
                        LeaderElectionEventPublisherCallbackLogger.log.warn(e) {
                            "LeaderElectionEventPublisher $callbackName callback failed and was ignored. " +
                                "lockName=${event.lockName}"
                        }
                    }
            }
        }
        .launchIn(scope)

    return AutoCloseable { job.cancel() }
}

private object LeaderElectionEventPublisherCallbackLogger : KLogging()

/**
 * `LeaderElectionListenerRegistry`는 leader election event를 관찰하거나 전달하는 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface LeaderElectionListenerRegistry {

    /**
     * `addListener`는 leader election event listener를 등록하고 해제 handle을 반환합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param listener `listener` 호출 또는 상태 계산에 필요한 값입니다.
     */
    fun addListener(listener: LeaderElectionListener): AutoCloseable

    /**
     * `removeListener`는 등록된 leader election event listener를 제거합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param listener `listener` 호출 또는 상태 계산에 필요한 값입니다.
     */
    fun removeListener(listener: LeaderElectionListener): Boolean
}

/**
 * `LeaderElectionListenerSupport`는 leader election event를 관찰하거나 전달하는 계약입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
open class LeaderElectionListenerSupport : LeaderElectionListenerRegistry {

    private val listeners = CopyOnWriteArrayList<LeaderElectionListener>()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable {
        listeners.addIfAbsent(listener)
        return AutoCloseable { removeListener(listener) }
    }

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.remove(listener)

    /**
     * `notifyElected` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param leader 현재 lock을 점유한 leader lease입니다. 비어 있으면 leadership이 없는 상태입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun notifyElected(lockName: String, leader: LeaderLease? = null) {
        notify(lockName, "onElected") { it.onElected(lockName, leader) }
    }

    /**
     * `notifyRevoked` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun notifyRevoked(lockName: String) {
        notify(lockName, "onRevoked") { it.onRevoked(lockName) }
    }

    /**
     * `notifySkipped` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
            runCatching { callback(listener) }
                .onFailure { e ->
                    log.warn(e) {
                        "LeaderElectionListener $callbackName failed and was ignored. lockName=$lockName"
                    }
                }
        }
    }

    private companion object : KLogging()
}
