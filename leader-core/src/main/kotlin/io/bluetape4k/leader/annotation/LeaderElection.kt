package io.bluetape4k.leader.annotation

/**
 * `LeaderElection` 선언은 leader election 계약에서 사용되는 annotation입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property name 호출자가 전달하는 이름 또는 key입니다.
 * @property waitTime leader lock 획득을 기다리는 최대 시간입니다.
 * @property leaseTime leadership을 보유할 수 있는 lease TTL입니다.
 * @property minLeaseTime 작업이 빨리 끝나더라도 lease를 최소로 유지할 시간입니다.
 * @property autoExtend 작업 실행 중 lease를 주기적으로 연장할지 결정합니다.
 * @property streamBounded `streamBounded` 호출 또는 상태 계산에 필요한 값입니다.
 * @property bean `bean` 호출 또는 상태 계산에 필요한 값입니다.
 * @property failureMode `failureMode` 호출 또는 상태 계산에 필요한 값입니다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElection(
    val name: String,
    val waitTime: String = "",
    val leaseTime: String = "",
    val minLeaseTime: String = "PT0S",
    val autoExtend: Boolean = false,
    val streamBounded: Boolean = false,
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
)
