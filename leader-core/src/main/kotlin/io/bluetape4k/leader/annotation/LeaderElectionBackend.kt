package io.bluetape4k.leader.annotation

/**
 * `LeaderElectionBackend` 선언은 leader election 계약에서 사용되는 annotation입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property bean `bean` 호출 또는 상태 계산에 필요한 값입니다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElectionBackend(val bean: String)
