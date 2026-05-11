package io.bluetape4k.leader.hazelcast.internal

import com.hazelcast.core.HazelcastException
import com.hazelcast.spi.exception.RetryableHazelcastException
import com.hazelcast.spi.exception.TargetNotMemberException
import com.hazelcast.spi.exception.WrongTargetException
import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind

/**
 * Hazelcast backend exception 분류 — T12 PR 7 (Issue #79).
 *
 * ## 동작/계약
 * - [RetryableHazelcastException] / [TargetNotMemberException] / [WrongTargetException] →
 *   [BackendErrorKind.TRANSIENT] (재시도 가능 — 클러스터 이벤트 / 멤버 이탈)
 * - 그 외 [HazelcastException] → [BackendErrorKind.NON_TRANSIENT] (safe default)
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록.
 */
internal object HazelcastBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RetryableHazelcastException -> BackendErrorKind.TRANSIENT
        is TargetNotMemberException -> BackendErrorKind.TRANSIENT
        is WrongTargetException -> BackendErrorKind.TRANSIENT
        is HazelcastException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}
