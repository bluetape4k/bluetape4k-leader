package io.bluetape4k.leader.audit

/**
 * leader election lifecycle event의 외부 관찰 결과입니다.
 *
 * 이 값은 선출, 회수, contention에 따른 skip을 low-cardinality 상태로 표현합니다.
 */
enum class LeaderAuditLifecycleOutcome {
    /** leadership을 획득했습니다. */
    ELECTED,

    /** 기존 leadership이 회수되었습니다. */
    REVOKED,

    /** contention으로 leadership 획득을 건너뛰었습니다. */
    SKIPPED,
}
