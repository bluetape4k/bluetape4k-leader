package io.bluetape4k.leader.spring.adapter

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.spring.LeaderProperties

/**
 * [LeaderProperties] → 백엔드별 Options 변환 어댑터.
 *
 * 각 백엔드(Mongo, Exposed JDBC/R2DBC)는 자체 옵션 클래스를 갖지만, v1.0에서는 공통 속성만 노출하며
 * 백엔드 고유 옵션(`retryDelay`, `retryStrategy`, `recordHistory`, `lockOwner`)은 기본값을 사용합니다.
 *
 * 백엔드 고유 옵션 노출은 후속 이슈로 분리됩니다.
 */
internal object PropertiesAdapter {

    /** 공통 [LeaderElectionOptions] 변환. */
    fun toCommonElection(props: LeaderProperties): LeaderElectionOptions =
        LeaderElectionOptions(waitTime = props.waitTime, leaseTime = props.leaseTime)

    /** 공통 [LeaderGroupElectionOptions] 변환. */
    fun toCommonGroup(props: LeaderProperties): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = props.group.maxLeaders,
            waitTime = props.group.waitTime,
            leaseTime = props.group.leaseTime,
        )
}
