package io.bluetape4k.leader.annotation

/**
 * 함수 실행을 여러 leader slot을 허용하는 그룹 lock으로 감싸는 AspectJ 위빙 대상 annotation입니다.
 * `name`은 SpEL 식이며 `maxLeaders`는 2 이상이어야 합니다.
 * @property name 그룹 lock 이름으로 평가할 SpEL 식
 * @property maxLeaders 동시에 허용할 slot 수. 실행 시 2 이상의 값을 지정해야 하며 기본 sentinel `-1`은 유효한 설정이 아닙니다.
 * @property waitTime slot 획득 대기 시간. 빈 문자열이면 공통 대기 시간 설정을 사용합니다.
 * @property leaseTime slot lease 시간. 빈 문자열이면 공통 lease 설정을 사용합니다.
 * @property minLeaseTime 작업 완료 후에도 lease를 유지할 최소 시간이며 기본값은 0입니다.
 * @property bean 사용할 group elector factory bean 이름. 빈 문자열이면 자동으로 선택합니다.
 * @property failureMode backend 오류 처리 방식. 기본 `INHERIT`는 공통 AOP 설정을 따릅니다.
 * @property useDbTime Exposed JDBC/R2DBC backend에서 DB server 시간을 사용하도록 요청합니다.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderGroupElection(
    val name: String,
    val maxLeaders: Int = -1,
    val waitTime: String = "",
    val leaseTime: String = "",
    val minLeaseTime: String = "PT0S",
    val bean: String = "",
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
    val useDbTime: Boolean = false,
)
