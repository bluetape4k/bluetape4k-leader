package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.flow.Flow
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 리더 선출 실행 생명주기 이벤트를 수신하는 동기 리스너입니다.
 *
 * ## 동작/계약
 * - [onElected]는 해당 호출이 리더 또는 그룹 슬롯을 획득하고 사용자 작업을 실행하기 직전에 호출됩니다.
 * - [onSkipped]는 대기 시간 안에 리더 또는 그룹 슬롯을 획득하지 못해 사용자 작업이 실행되지 않을 때 호출됩니다.
 * - [onRevoked]는 현재 구현에서 외부 lease loss 감지가 아니라, 이 호출이 보유하던 리더십/슬롯을 반납한 뒤 호출됩니다.
 * - 리스너 일반 예외는 리더 작업 결과를 바꾸지 않도록 기록 후 무시됩니다.
 *
 * ```kotlin
 * val listener = object : LeaderElectionListener {
 *     override fun onElected(lockName: String) {
 *         println("elected: $lockName")
 *     }
 * }
 * ```
 */
interface LeaderElectionListener {

    /** 리더 또는 그룹 슬롯을 획득했을 때 호출됩니다. */
    fun onElected(lockName: String) = Unit

    /** 리더십 또는 그룹 슬롯을 반납한 뒤 호출됩니다. */
    fun onRevoked(lockName: String) = Unit

    /** 리더 또는 그룹 슬롯을 획득하지 못해 사용자 작업을 건너뛰었을 때 호출됩니다. */
    fun onSkipped(lockName: String) = Unit
}

/**
 * 리더 선출 생명주기 이벤트입니다.
 *
 * suspend 환경에서는 callback 대신 [LeaderElectionEventPublisher.events]를 collect하면 리더 실행 생명주기를 stream으로
 * 관찰할 수 있습니다.
 */
sealed interface LeaderElectionEvent {
    /** 이벤트가 발생한 락 이름입니다. */
    val lockName: String

    /**
     * Leader or group slot acquisition event.
     *
     * ## Behavior / Contract
     * - [leaderId] is the identity of the node that won the election. Populated by local electors
     *   and backends that carry identity; `null` when identity is unavailable (e.g. decorator path).
     * - [leaseExpiry] is the absolute time at which the lease expires. Currently always `null`;
     *   reserved for future backend implementations that can report expiry at election time.
     */
    data class Elected @JvmOverloads constructor(
        override val lockName: String,
        val leaderId: String? = null,
        val leaseExpiry: Instant? = null,
    ) : LeaderElectionEvent, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** 리더십 또는 그룹 슬롯을 반납한 이벤트입니다. */
    data class Revoked(override val lockName: String) : LeaderElectionEvent

    /** 리더 또는 그룹 슬롯을 획득하지 못해 사용자 작업을 건너뛴 이벤트입니다. */
    data class Skipped(override val lockName: String) : LeaderElectionEvent
}

/**
 * 리더 선출 생명주기 이벤트 stream을 노출하는 계약입니다.
 */
interface LeaderElectionEventPublisher {

    /**
     * 리더 선출 이벤트 stream입니다.
     *
     * 구현체는 내부적으로 hot event source를 사용하며, collector가 활성화된 동안 발생한 이벤트를 전달합니다.
     */
    val events: Flow<LeaderElectionEvent>
}

/**
 * [LeaderElectionListener] 등록/해제 계약입니다.
 *
 * 구현체는 반환된 [AutoCloseable]을 닫는 방식으로도 같은 리스너를 해제할 수 있습니다.
 */
interface LeaderElectionListenerRegistry {

    /**
     * [listener]를 등록하고, 닫으면 등록을 해제하는 handle을 반환합니다.
     */
    fun addListener(listener: LeaderElectionListener): AutoCloseable

    /**
     * [listener] 등록을 해제합니다.
     *
     * @return 실제로 등록되어 있던 리스너가 제거되었으면 `true`
     */
    fun removeListener(listener: LeaderElectionListener): Boolean
}

/**
 * [LeaderElectionListenerRegistry]의 thread-safe 기본 구현입니다.
 *
 * [CopyOnWriteArrayList]를 사용하므로 리스너 호출 중 등록/해제가 발생해도 현재 dispatch는 안정적인 snapshot을 사용합니다.
 */
open class LeaderElectionListenerSupport : LeaderElectionListenerRegistry {

    private val listeners = CopyOnWriteArrayList<LeaderElectionListener>()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable {
        listeners.addIfAbsent(listener)
        return AutoCloseable { removeListener(listener) }
    }

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.remove(listener)

    /** 등록된 리스너에 선출 이벤트를 발행합니다. */
    fun notifyElected(lockName: String) {
        notify(lockName, "onElected") { it.onElected(lockName) }
    }

    /** 등록된 리스너에 반납 이벤트를 발행합니다. */
    fun notifyRevoked(lockName: String) {
        notify(lockName, "onRevoked") { it.onRevoked(lockName) }
    }

    /** 등록된 리스너에 skip 이벤트를 발행합니다. */
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
