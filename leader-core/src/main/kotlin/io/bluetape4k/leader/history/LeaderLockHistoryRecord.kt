package io.bluetape4k.leader.history

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant

/**
 * `LeaderLockHistoryRecord`는 leader lock lifecycle event를 저장하는 audit record입니다.
 *
 * @property lockName 기록 대상 lock 이름입니다. 빈 문자열은 허용되지 않으며 이력 조회의 기준 키로 사용됩니다.
 * @property token backend lock 소유권 token입니다. 민감정보이므로 직접 출력하지 마세요. `toString()`은 값을 가립니다.
 * @property kind single leader election인지 group leader election인지 나타내는 분류입니다.
 * @property acquiredAt lock을 획득한 wall-clock 시각입니다.
 * @property lockedUntil backend가 보고한 lease 만료 절대 시각입니다.
 * @property nodeId lock을 획득한 노드 식별자입니다. backend에서 식별자를 제공하지 않으면 `null`입니다.
 * @property finishedAt 사용자 작업이 끝난 절대 시각입니다. 작업 중이거나 종료 시각을 기록하지 않았으면 `null`입니다.
 * @property durationMs 작업 시작부터 종료까지의 경과 시간(밀리초)입니다. 완료 전에는 `null`입니다.
 * @property status 기록 시점의 이력 상태입니다. 상태를 제공하지 않는 저장 경로에서는 `null`이며, 기본값도 `null`입니다.
 * @property errorType 작업 실패 원인의 예외 클래스 이름입니다. 실패 정보가 없으면 `null`입니다.
 * @property errorMessage 로그 위험 문자를 정제한 실패 메시지입니다. recorder는 UTF-8 기준 최대 512바이트로 자르고, 값이 없으면 `null`입니다.
 * @property slotId group election에서 점유한 슬롯 ID입니다. 단일 leader 이력이나 슬롯 정보가 없으면 `null`입니다.
 * @property metadata 이력에 함께 저장하는 caller 제공 key-value 문맥입니다. recorder는 최대 16개 항목과 값당 256자를 남기므로 민감정보를 넣지 마세요.
 */
@ConsistentCopyVisibility
data class LeaderLockHistoryRecord private constructor(
    val lockName: String,
    val token: String,
    val kind: io.bluetape4k.leader.LockIdentity.AnnotationKind,
    val acquiredAt: Instant,
    val lockedUntil: Instant,
    val nodeId: String?,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    /**
     * `status`는 leader election의 현재 상태를 표현합니다.
     */
    val status: LeaderHistoryStatus? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val slotId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
): Serializable {

    /**
     * recorder가 정제한 실패 메시지와 metadata를 반영한 새 record를 반환합니다. 기존 인스턴스는 변경하지 않습니다.
     * @param errorMessage 로그 위험 문자를 정제하고 UTF-8 기준 최대 512바이트로 자른 실패 메시지입니다. 없으면 `null`입니다.
     * @param metadata 최대 16개 항목과 값당 256자로 정제된 이력 문맥입니다.
     */
    internal fun withSanitizedContent(errorMessage: String?, metadata: Map<String, String>): LeaderLockHistoryRecord =
        copy(errorMessage = errorMessage, metadata = metadata)

    // 이 record를 문자열 보간으로 로그에 남길 때 credential이 노출되지 않도록 token을 가립니다.
    override fun toString(): String =
        "LeaderLockHistoryRecord(lockName=$lockName, token=***, kind=$kind, acquiredAt=$acquiredAt, " +
                "lockedUntil=$lockedUntil, nodeId=$nodeId, status=$status, slotId=$slotId, " +
                "durationMs=$durationMs, errorType=$errorType)"

    companion object: KLogging() {
        private const val serialVersionUID = 1L

        /**
         * recorder가 이력 실패 메시지에 남기는 UTF-8 바이트의 최대 길이입니다.
         */
        const val MAX_ERROR_MESSAGE_BYTES = 512

        /**
         * recorder가 한 이력에 남기는 metadata key-value 항목의 최대 개수입니다.
         */
        const val MAX_METADATA_KEYS = 16

        /**
         * recorder가 각 metadata 값에서 남기는 최대 문자 수입니다.
         */
        const val MAX_METADATA_VALUE_LENGTH = 256

        /**
         * 이력 record를 생성합니다. `lockName`과 `token`은 빈 문자열일 수 없으며 `metadata`는 새 map으로 복사합니다.
         * @param lockName lock 이력의 조회 기준 이름입니다. 빈 문자열이면 거부됩니다.
         * @param token backend가 lock 소유권을 확인하는 값입니다. 빈 문자열이면 거부되며 민감정보로 취급해야 합니다.
         * @param kind single 또는 group election을 구분합니다.
         * @param acquiredAt lock 획득 절대 시각입니다.
         * @param lockedUntil backend가 보고한 lease 만료 절대 시각입니다.
         * @param nodeId lock을 획득한 노드 ID입니다. 알 수 없으면 `null`입니다.
         * @param finishedAt 작업 종료 절대 시각입니다. 아직 실행 중이면 `null`입니다.
         * @param durationMs 작업 실행 경과 시간(밀리초)입니다. 아직 완료되지 않았으면 `null`입니다.
         * @param status 생성 시점에 관측한 이력 상태입니다. 알 수 없으면 `null`입니다.
         * @param errorType 실패 원인의 예외 클래스 이름입니다. 실패 정보가 없으면 `null`입니다.
         * @param errorMessage 실패 원인의 메시지입니다. recorder가 정제하고 UTF-8 기준 512바이트 이내로 자릅니다.
         * @param slotId group election에서 획득한 슬롯 ID입니다. 단일 leader 또는 정보가 없으면 `null`입니다.
         * @param metadata 이력에 함께 저장할 caller 문맥입니다. 생성 시 복사되며 recorder가 크기와 로그 문자를 정제합니다.
         * @return 인자를 담고 metadata map을 복사한 history record입니다.
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
