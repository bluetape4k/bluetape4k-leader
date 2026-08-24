package io.bluetape4k.leader

/**
 * 요청별 lease handle이 관찰한 ownership 상태입니다.
 */
enum class LeaseOwnershipStatus {
    /** backend가 현재 handle의 fencing generation을 보유함을 확인했습니다. */
    HELD,

    /** backend가 해당 generation을 보유하지 않음을 확인했습니다. */
    NOT_HELD,

    /** release, timeout 또는 backend 오류로 ownership을 확정할 수 없습니다. */
    UNKNOWN,
}
