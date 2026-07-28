package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * `LeaderLockHistoryRecord`는 leader lock lifecycle event를 저장하는 audit record입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @property token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
 * @property kind single leader election인지 group leader election인지 나타내는 분류입니다.
 * @property acquiredAt lock을 획득한 wall-clock 시각입니다.
 * @property lockedUntil backend가 보고한 lease 만료 시각입니다.
 * @property nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
 * @property finishedAt 사용자 작업이 종료된 wall-clock 시각입니다. 실행 중이면 null입니다.
 * @property durationMs 사용자 작업 실행 시간입니다. 실행 중이면 null입니다.
 * @property status history record의 현재 또는 최종 상태입니다.
 * @property errorType 작업 실패 시 예외의 fully-qualified class 이름입니다.
 * @property errorMessage 작업 실패 시 정제되고 길이가 제한된 예외 메시지입니다.
 * @property slotId group election backend가 slot을 식별할 때 쓰는 값입니다.
 * @property metadata 호출자가 제공한 key-value audit context입니다. recorder 계층에서 크기와 길이가 제한됩니다.
 */
@ConsistentCopyVisibility
data class LeaderLockHistoryRecord private constructor(
    /**
     * `lockName` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val lockName: String,
    /**
     * `token` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val token: String,
    /**
     * `kind` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val kind: io.bluetape4k.leader.LockIdentity.AnnotationKind,
    /**
     * `acquiredAt` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val acquiredAt: Instant,
    /**
     * `lockedUntil` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val lockedUntil: Instant,
    /**
     * `nodeId` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val nodeId: String?,
    /**
     * `finishedAt` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val finishedAt: Instant? = null,
    /**
     * `durationMs` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val durationMs: Long? = null,
    /**
     * `status`는 leader election의 현재 상태를 표현합니다.
     */
    val status: LeaderHistoryStatus? = null,
    /**
     * `errorType` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val errorType: String? = null,
    /**
     * `errorMessage` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val errorMessage: String? = null,
    /**
     * `slotId` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val slotId: String? = null,
    /**
     * `metadata` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val metadata: Map<String, String> = emptyMap(),
) : Serializable {

    /**
     * `withSanitizedContent` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param errorMessage 작업 실패 시 정제되고 길이가 제한된 예외 메시지입니다.
     * @param metadata 호출자가 제공한 key-value audit context입니다. recorder 계층에서 크기와 길이가 제한됩니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    internal fun withSanitizedContent(errorMessage: String?, metadata: Map<String, String>): LeaderLockHistoryRecord =
        copy(errorMessage = errorMessage, metadata = metadata)

    // 이 record를 문자열 보간으로 로그에 남길 때 credential이 노출되지 않도록 token을 가립니다.
    override fun toString(): String =
        "LeaderLockHistoryRecord(lockName=$lockName, token=***, kind=$kind, acquiredAt=$acquiredAt, " +
        "lockedUntil=$lockedUntil, nodeId=$nodeId, status=$status, slotId=$slotId, " +
        "durationMs=$durationMs, errorType=$errorType)"

    companion object : KLogging() {
        private const val serialVersionUID = 1L

        /**
         * `MAX_ERROR_MESSAGE_BYTES` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        const val MAX_ERROR_MESSAGE_BYTES = 512

        /**
         * `MAX_METADATA_KEYS` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        const val MAX_METADATA_KEYS = 16

        /**
         * `MAX_METADATA_VALUE_LENGTH` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        const val MAX_METADATA_VALUE_LENGTH = 256

        /**
         * `invoke` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
         * @param token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
         * @param kind single leader election인지 group leader election인지 나타내는 분류입니다.
         * @param acquiredAt lock을 획득한 wall-clock 시각입니다.
         * @param lockedUntil backend가 보고한 lease 만료 시각입니다.
         * @param nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
         * @param finishedAt 사용자 작업이 종료된 wall-clock 시각입니다. 실행 중이면 null입니다.
         * @param durationMs 사용자 작업 실행 시간입니다. 실행 중이면 null입니다.
         * @param status history record의 현재 또는 최종 상태입니다.
         * @param errorType 작업 실패 시 예외의 fully-qualified class 이름입니다.
         * @param errorMessage 작업 실패 시 정제되고 길이가 제한된 예외 메시지입니다.
         * @param slotId group election backend가 slot을 식별할 때 쓰는 값입니다.
         * @param metadata 호출자가 제공한 key-value audit context입니다. recorder 계층에서 크기와 길이가 제한됩니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        operator fun invoke(
            lockName: String,
            token: String,
            kind: io.bluetape4k.leader.LockIdentity.AnnotationKind,
            acquiredAt: Instant,
            lockedUntil: Instant,
            nodeId: String? = null,
            finishedAt: Instant? = null,
            durationMs: Long? = null,
            status: LeaderHistoryStatus? = null,
            errorType: String? = null,
            errorMessage: String? = null,
            slotId: String? = null,
            metadata: Map<String, String> = emptyMap(),
        ): LeaderLockHistoryRecord {
            lockName.requireNotBlank("lockName")
            token.requireNotBlank("token")
            return LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = kind,
                acquiredAt = acquiredAt,
                lockedUntil = lockedUntil,
                nodeId = nodeId,
                finishedAt = finishedAt,
                durationMs = durationMs,
                status = status,
                errorType = errorType,
                errorMessage = errorMessage,
                slotId = slotId,
                metadata = metadata.toMap(),
            )
        }
    }
}
