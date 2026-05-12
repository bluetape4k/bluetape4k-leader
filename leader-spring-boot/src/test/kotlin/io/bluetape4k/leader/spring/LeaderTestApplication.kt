package io.bluetape4k.leader.spring

import org.springframework.boot.SpringBootConfiguration

/**
 * leader-spring-boot 모듈 `@SpringBootTest` 전용 최소 진입점.
 *
 * `@EnableAutoConfiguration` 을 포함하지 않아 컴포넌트 스캔 없이
 * `@ImportAutoConfiguration` 으로 명시한 AutoConfig 만 로딩.
 */
@SpringBootConfiguration
open class LeaderTestApplication
