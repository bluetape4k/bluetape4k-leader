package io.bluetape4k.leader.spring.adapter

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.spring.LeaderProperties
import kotlin.time.toKotlinDuration

/** Spring Boot `LeaderProperties`를 core elector option으로 변환하는 내부 adapter입니다. */
internal object PropertiesAdapter {

    /** `waitTime`과 `leaseTime`을 Kotlin duration으로 변환한 단일 leader 옵션을 반환합니다. */
    fun toCommonElection(props: LeaderProperties): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = props.waitTime.toKotlinDuration(),
            leaseTime = props.leaseTime.toKotlinDuration(),
        )

    /** `maxLeaders`, wait/lease time, `useDbTime`을 포함한 group leader 옵션을 반환합니다. */
    fun toCommonGroup(props: LeaderProperties): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = props.group.maxLeaders,
            waitTime = props.group.waitTime.toKotlinDuration(),
            leaseTime = props.group.leaseTime.toKotlinDuration(),
            useDbTime = props.group.useDbTime,
        )
}
