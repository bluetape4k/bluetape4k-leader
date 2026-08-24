package io.bluetape4k.leader

/** 요청별 lease cleanup의 terminal 결과입니다. */
enum class LeaseCleanupResult {
    /** fencing-aware backend release가 확인되었습니다. */
    RELEASED,

    /** 해당 generation이 이미 ownership을 잃었습니다. */
    NOT_HELD,

    /** deadline 안에 증명하지 못해 residual registry로 이관되었습니다. */
    RESIDUAL_TRANSFERRED,
}
