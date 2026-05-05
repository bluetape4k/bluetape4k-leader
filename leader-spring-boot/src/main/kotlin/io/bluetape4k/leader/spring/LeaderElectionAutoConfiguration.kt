package io.bluetape4k.leader.spring

import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.spring.backend.ExposedJdbcLeaderConfiguration
import io.bluetape4k.leader.spring.backend.ExposedR2dbcLeaderConfiguration
import io.bluetape4k.leader.spring.backend.HazelcastLeaderConfiguration
import io.bluetape4k.leader.spring.backend.LettuceLeaderConfiguration
import io.bluetape4k.leader.spring.backend.MongoLeaderConfiguration
import io.bluetape4k.leader.spring.backend.RedissonLeaderConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

/**
 * bluetape4k-leader Spring Boot 자동 구성 진입점.
 *
 * `LeaderElection` 클래스가 classpath에 존재하는 경우 활성화되며, 각 백엔드별 하위 구성을
 * `@Import`로 가져옵니다. 백엔드 활성화 조건은 각 Configuration 클래스에서 `@ConditionalOnBean` /
 * `@ConditionalOnClass`로 검사됩니다.
 *
 * Local fallback은 별도의 `LocalLeaderConfiguration`이 `@AutoConfiguration(after=...)`로 선언되어
 * 모든 백엔드 평가 후 활성화됩니다.
 *
 * @see LeaderProperties yaml `bluetape4k.leader.*` 속성 바인딩
 */
@AutoConfiguration
@ConditionalOnClass(LeaderElection::class)
@EnableConfigurationProperties(LeaderProperties::class)
@Import(
    RedissonLeaderConfiguration::class,
    LettuceLeaderConfiguration::class,
    MongoLeaderConfiguration::class,
    HazelcastLeaderConfiguration::class,
    ExposedJdbcLeaderConfiguration::class,
    ExposedR2dbcLeaderConfiguration::class,
)
class LeaderElectionAutoConfiguration
