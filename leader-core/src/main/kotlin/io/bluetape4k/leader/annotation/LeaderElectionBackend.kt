package io.bluetape4k.leader.annotation

/**
 * 특정 함수, 클래스 또는 파일에 적용할 leader elector factory bean을 지정합니다.
 * @property bean Spring context에서 선택할 factory bean 이름
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.FILE)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class LeaderElectionBackend(val bean: String)
