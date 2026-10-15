package io.bluetape4k.leader.annotation

/**
 * 함수 실행을 단일 leader lock으로 감싸는 AspectJ 위빙 대상 annotation입니다.
 * `name`은 SpEL 식이며, 빈 시간 문자열은 공통 AOP 설정을 사용합니다.
 * @property name lock 이름으로 평가할 SpEL 식
 * @property waitTime lock 획득 대기 시간. 빈 문자열이면 공통 대기 시간 설정을 사용합니다.
 * @property leaseTime lock lease 시간. 빈 문자열이면 공통 lease 설정을 사용합니다.
 * @property minLeaseTime 작업 완료 후에도 lease를 유지할 최소 시간이며 기본값은 0입니다.
 * @property autoExtend 실행 중 lease 자동 연장을 활성화합니다.
 * @property streamBounded `autoExtend` 없이 lease 시간 안에 완료되는 stream임을 선언합니다.
 * @property bean 사용할 elector factory bean 이름. 빈 문자열이면 자동으로 선택합니다.
 * @property failureMode backend 오류 처리 방식. 기본 `INHERIT`는 공통 AOP 설정을 따릅니다.
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
