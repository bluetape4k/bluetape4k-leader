package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector

/**
 * [LeaderElectionPlugin] 의 설정 클래스입니다.
 *
 * ## 동작/계약
 * - [leaderElection] 은 필수 항목으로, `install(LeaderElectionPlugin) { ... }` 블록에서 반드시 설정해야 합니다.
 * - [leaderGroupElection] 은 선택 항목으로, 멀티 리더(그룹 선출)가 필요한 애플리케이션에서만 설정합니다.
 * - 미설정 시 플러그인 설치 시점에 [IllegalArgumentException] 이 발생합니다.
 *
 * ```kotlin
 * fun Application.module() {
 *     install(LeaderElectionPlugin) {
 *         leaderElection = RedissonSuspendLeaderElector(redissonClient)
 *         leaderGroupElection = RedissonSuspendLeaderGroupElector(redissonClient)
 *     }
 * }
 * ```
 *
 * @property leaderElection 단일 리더 선출 구현체 (필수)
 * @property leaderGroupElection 멀티 리더 선출 구현체 (선택)
 */
class LeaderElectionPluginConfig {

    /** 단일 리더 선출 구현체. 플러그인 사용 전 반드시 설정해야 합니다. */
    var leaderElection: SuspendLeaderElector? = null

    /** 멀티 리더(그룹) 선출 구현체. 필요 시 설정합니다. */
    var leaderGroupElection: SuspendLeaderGroupElector? = null
}
