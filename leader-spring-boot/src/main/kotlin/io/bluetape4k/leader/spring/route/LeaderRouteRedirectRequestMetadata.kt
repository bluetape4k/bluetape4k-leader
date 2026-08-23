package io.bluetape4k.leader.spring.route

import java.io.Serializable

/**
 * MVC/WebFlux 요청에서 forwarded-header 변환 전에 애플리케이션이 보존한 metadata를 읽는
 * 호출자 provider입니다. 일반 interceptor/filter ordering으로 raw 근거를 복원하지 않습니다.
 */
fun interface LeaderRouteRedirectRequestMetadataProvider<T> {
    fun capture(request: T): LeaderRouteRedirectRequestMetadata
}

/** forwarded trust 판단에 필요한 원시 request metadata입니다. */
data class LeaderRouteRedirectRequestMetadata(
    val forwardedHeadersPresent: Boolean?,
    val transportPeerAddress: String?,
) : Serializable {
    private companion object {
        const val serialVersionUID: Long = 1L
    }
}
