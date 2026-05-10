package io.bluetape4k.leader.examples.migration

/**
 * [MigrationGate.runMigration] 의 실행 결과.
 *
 * ## 동작/계약
 *
 * 4가지 분기로 마이그레이션 시도의 모든 결과를 표현한다:
 * - [Migrated]: 본 인스턴스가 리더로 선출되어 마이그레이션을 성공적으로 수행
 * - [AlreadyApplied]: 마이그레이션이 이미 적용된 상태 (precheck/in-lock recheck/post-skip check 어느 단계에서든)
 * - [Skipped]: 락 미획득 + 마커 미생성 — 다른 인스턴스가 처리 중이거나 wait 시간 초과
 * - [Failed]: 마이그레이션 lambda 실행 중 예외 발생 — 락은 자동 해제됨
 *
 * 호출자는 sealed interface 의 모든 분기를 명시적으로 처리해야 한다 (exhaustive when).
 */
sealed interface Outcome {
    val migrationId: String

    /** 본 인스턴스가 리더로 마이그레이션 수행 완료. */
    data class Migrated(
        override val migrationId: String,
        val durationMs: Long,
    ): Outcome

    /** 마이그레이션이 이미 적용된 상태 (마커 확인됨). */
    data class AlreadyApplied(
        override val migrationId: String,
    ): Outcome

    /** 락 미획득 + 마커 미생성 — 다른 인스턴스 처리 중일 가능성. */
    data class Skipped(
        override val migrationId: String,
        val reason: String,
    ): Outcome

    /** 마이그레이션 실행 중 예외 발생. */
    data class Failed(
        override val migrationId: String,
        val cause: Throwable,
        val durationMs: Long,
    ): Outcome
}
