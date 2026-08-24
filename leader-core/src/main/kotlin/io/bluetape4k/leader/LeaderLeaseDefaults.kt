package io.bluetape4k.leader

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 요청별 lease capability의 공통 bounded 기본값입니다. */
object LeaderLeaseDefaults {
    /** blocking acquire가 기다리는 기본 상한입니다. */
    val maxBlockingWaitTime: Duration = 5.seconds

    /** public no-arg release가 사용할 수 있는 최대 deadline입니다. */
    val PUBLIC_RELEASE_TIMEOUT: Duration = 30.seconds

    /** 내부 구현에서 사용하는 읽기 쉬운 별칭입니다. */
    val maxReleaseDeadline: Duration get() = PUBLIC_RELEASE_TIMEOUT

    /** residual registry의 기본 상한입니다. */
    const val maxResidualLeases: Int = 1024
}
