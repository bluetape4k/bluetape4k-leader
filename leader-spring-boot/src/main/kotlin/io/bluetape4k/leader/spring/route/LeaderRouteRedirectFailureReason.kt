package io.bluetape4k.leader.spring.route

/** redirect 후보가 폐기된 이유를 저카디널리티로 표현합니다. */
internal enum class LeaderRouteRedirectFailureReason {
    STALE_LEASE,
    UNAVAILABLE,
    NULL_TARGET,
    URI_REJECTED,
    UNTRUSTED_PROXY,
    METADATA_UNKNOWN,
    CALLBACK_FAILURE,
}

/** 관측 hook에 전달하는 framework 값입니다. */
internal enum class LeaderRouteRedirectFramework {
    MVC,
    WEBFLUX,
}
