package io.bluetape4k.leader.examples.migration

/**
 * example workflow 계약을 설명하는 한국어 KDoc입니다.
 */
sealed interface Outcome {
    val migrationId: String

    /**
     * `Migrated`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property migrationId example workflow 계약에서 `migrationId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property durationMs example workflow 계약에서 `durationMs` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Migrated(
        override val migrationId: String,
        val durationMs: Long,
    ): Outcome

    /**
     * `AlreadyApplied`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property migrationId example workflow 계약에서 `migrationId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class AlreadyApplied(
        override val migrationId: String,
    ): Outcome

    /**
     * `Skipped`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property migrationId example workflow 계약에서 `migrationId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property reason example workflow 계약에서 `reason` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Skipped(
        override val migrationId: String,
        val reason: String,
    ): Outcome

    /**
     * `Failed`는 example workflow에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property migrationId example workflow 계약에서 `migrationId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property cause example workflow 계약에서 `cause` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property durationMs example workflow 계약에서 `durationMs` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Failed(
        override val migrationId: String,
        val cause: Throwable,
        val durationMs: Long,
    ): Outcome
}
