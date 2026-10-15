package io.bluetape4k.leader.spring.properties

import java.io.Serializable

/**
 * 시작 시 leader 관련 bean과 설정을 검사하는 진단 기능의 Spring Boot 설정입니다.
 *
 * @property enabled 진단 실행 여부이며 기본값은 `true`입니다.
 * @property strict 진단 오류를 애플리케이션 시작 실패로 승격할지 여부이며 기본값은 `false`입니다.
 * @property includeBeanNames 진단 결과에 검사한 bean 이름을 포함할지 여부이며 기본값은 `true`입니다.
 */
data class LeaderDiagnosticsProperties(
    val enabled: Boolean = true,
    val strict: Boolean = false,
    val includeBeanNames: Boolean = true,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
