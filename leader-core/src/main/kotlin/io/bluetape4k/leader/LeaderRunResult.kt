package io.bluetape4k.leader

/**
 * [LeaderElector.runIfLeaderResult] / [LeaderGroupElector.runIfGroupLeaderResult] 호출 결과를 나타내는 sealed interface.
 *
 * ## 목적
 * [LeaderElector.runIfLeader]는 (a) lock 미획득 시와 (b) action()이 null을 반환할 때
 * 모두 `null`을 반환하여 호출자가 두 경우를 구분할 수 없습니다.
 * 이 타입은 그 모호성을 제거합니다.
 *
 * ## 상태
 * - [Elected]: 리더 선출 성공 — action이 실행됨. action 반환값이 null이어도 [Elected]
 * - [Skipped]: 리더 선출 실패 — lock 미획득으로 action이 실행되지 않음
 */
sealed interface LeaderRunResult<out T> {

    /**
     * 리더 선출 성공 — action 실행 완료.
     *
     * action 반환값이 null이어도 [Elected]로 분류됩니다.
     *
     * NOTE: T가 non-null 타입이어도 [value]는 `T?`로 선언됩니다.
     * 이는 `runIfLeader()` 반환 타입 `T?`를 통한 클로저 패턴의 제약입니다.
     */
    data class Elected<out T>(val value: T?) : LeaderRunResult<T>

    /** 리더 선출 실패 — lock 미획득으로 action이 실행되지 않음. */
    data object Skipped : LeaderRunResult<Nothing>
}
