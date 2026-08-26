package io.bluetape4k.leader

import java.io.Serializable
import java.util.concurrent.atomic.AtomicBoolean

/** 운영자가 process-local registry에 요청할 수 있는 management action입니다. */
enum class LeaderManagementAction {
    RELEASE,
}

/** management action의 외부에 노출 가능한 terminal outcome입니다. */
enum class LeaderManagementActionOutcome {
    RELEASED,
    INVALID_LOCK_NAME,
    NOT_REGISTERED,
    AMBIGUOUS,
    NOT_HELD,
    OWNERSHIP_UNKNOWN,
    RELEASE_UNCONFIRMED,
    RELEASE_FAILED,
    REGISTRY_CLOSED,
    ACTION_IN_PROGRESS,
    ACTION_ADMISSION_REJECTED,
    ACTION_TIMED_OUT,
}

/** backend 내부 객체나 예외를 노출하지 않는 management action 결과입니다. */
data class LeaderManagementActionResult(
    val action: LeaderManagementAction,
    val outcome: LeaderManagementActionOutcome,
    val mutationAttempted: Boolean,
) : Serializable {

    init {
        if (outcome == LeaderManagementActionOutcome.RELEASED) {
            require(mutationAttempted) { "RELEASED requires mutationAttempted=true" }
        }
        if (outcome in NON_MUTATING_OUTCOMES) {
            require(!mutationAttempted) { "$outcome cannot mutate a lease" }
        }
    }

    private companion object {
        private val NON_MUTATING_OUTCOMES = setOf(
            LeaderManagementActionOutcome.INVALID_LOCK_NAME,
            LeaderManagementActionOutcome.NOT_REGISTERED,
            LeaderManagementActionOutcome.AMBIGUOUS,
            LeaderManagementActionOutcome.NOT_HELD,
            LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN,
            LeaderManagementActionOutcome.REGISTRY_CLOSED,
            LeaderManagementActionOutcome.ACTION_IN_PROGRESS,
            LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED,
        )

        private const val serialVersionUID: Long = 1L
    }
}

/** registration token admission 결과입니다. */
enum class LeaderManagementRegistrationOutcome {
    ACCEPTED,
    INVALID_LOCK_NAME,
    CAPACITY_REJECTED,
    REGISTRY_CLOSED,
}

/** 등록 reference를 닫는 idempotent token입니다. */
class LeaderManagementRegistration internal constructor(
    val accepted: Boolean,
    val outcome: LeaderManagementRegistrationOutcome,
    private val onClose: () -> Unit = {},
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    init {
        require(accepted == (outcome == LeaderManagementRegistrationOutcome.ACCEPTED)) {
            "accepted must match registration outcome"
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

/** action observation에 기록할 adapter surface입니다. */
enum class LeaderManagementActionSurface {
    CORE,
    SPRING,
    KTOR,
}

/** registry 내부 action phase입니다. terminal observer에는 마지막 phase만 전달합니다. */
enum class LeaderManagementActionPhase {
    ADMITTED,
    PRECHECK,
    RELEASE_STARTED,
    POSTCHECK,
    TERMINALIZED,
    QUARANTINED,
}

/** quarantine이 발생한 원인을 low-cardinality로 표현합니다. */
enum class LeaderManagementQuarantineReason {
    CLEANUP_TIMEOUT,
    NON_INTERRUPTIBLE,
    CALLBACK_ERROR,
    CLOSE_TIMEOUT,
}

/** lock/actor/token/backend 정보를 포함하지 않는 sanitized terminal observation입니다. */
data class LeaderManagementActionObservation(
    val surface: LeaderManagementActionSurface,
    val outcome: LeaderManagementActionOutcome,
    val phase: LeaderManagementActionPhase,
    val mutationAttempted: Boolean,
    val quarantined: Boolean,
    val quarantineReason: LeaderManagementQuarantineReason? = null,
) {
    init {
        require(quarantined == (phase == LeaderManagementActionPhase.QUARANTINED)) {
            "quarantined must match the terminal phase"
        }
        require(quarantined || quarantineReason == null) {
            "non-quarantined observations cannot carry a quarantine reason"
        }
    }
}

/** action 결과를 sanitized 형태로 관찰하는 callback입니다. */
fun interface LeaderManagementActionObserver {
    fun onResult(observation: LeaderManagementActionObservation)
}

/** 공통 HTTP adapter가 사용할 status/retry 정책입니다. */
object LeaderManagementHttpContract {

    /** outcome을 framework-neutral HTTP status code로 변환합니다. */
    fun statusCode(outcome: LeaderManagementActionOutcome): Int = when (outcome) {
        LeaderManagementActionOutcome.RELEASED -> 200
        LeaderManagementActionOutcome.INVALID_LOCK_NAME -> 400
        LeaderManagementActionOutcome.NOT_REGISTERED -> 404
        LeaderManagementActionOutcome.AMBIGUOUS,
        LeaderManagementActionOutcome.NOT_HELD,
        LeaderManagementActionOutcome.ACTION_IN_PROGRESS,
        -> 409
        LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED -> 429
        LeaderManagementActionOutcome.OWNERSHIP_UNKNOWN,
        LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
        LeaderManagementActionOutcome.RELEASE_FAILED,
        LeaderManagementActionOutcome.REGISTRY_CLOSED,
        -> 503
        LeaderManagementActionOutcome.ACTION_TIMED_OUT -> 504
    }

    /** 이번 action 결과를 자동 재시도해도 안전하다는 보장을 제공하지 않습니다. */
    fun retryAllowed(@Suppress("UNUSED_PARAMETER") outcome: LeaderManagementActionOutcome): Boolean = false

    /** timeout의 mutation flag를 포함한 결과에도 동일한 no-retry 정책을 적용합니다. */
    fun retryAllowed(@Suppress("UNUSED_PARAMETER") result: LeaderManagementActionResult): Boolean = false
}
