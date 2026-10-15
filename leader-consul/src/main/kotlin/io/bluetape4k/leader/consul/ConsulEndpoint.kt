package io.bluetape4k.leader.consul

import java.io.Serializable
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `ConsulEndpoint`는 Consul backend leader election에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property baseUrl Consul HTTP API의 `http` 또는 `https` 기본 URI이며 host를 포함하고 query/fragment/user-info는 가질 수 없습니다.
 * @property datacenter 요청 query의 `dc` 값입니다. null이면 Consul 기본 datacenter를 사용하고, 값은 공백일 수 없습니다.
 * @property aclToken Consul ACL 인증 token입니다. null일 수 있으며 `toString()`에서는 항상 `***`로 가립니다.
 * @property requestTimeout 각 HTTP 요청의 양수 timeout이며 기본값은 5초입니다.
 */
data class ConsulEndpoint(
    val baseUrl: URI,
    val datacenter: String? = null,
    val aclToken: String? = null,
    val requestTimeout: Duration = DefaultRequestTimeout,
): Serializable {

    constructor(
        baseUrl: String,
        datacenter: String? = null,
        aclToken: String? = null,
        requestTimeout: Duration = DefaultRequestTimeout,
    ): this(URI.create(baseUrl), datacenter, aclToken, requestTimeout)

    init {
        require(baseUrl.scheme == "http" || baseUrl.scheme == "https") {
            "baseUrl scheme must be http or https. baseUrl=$baseUrl"
        }
        require(!baseUrl.host.isNullOrBlank()) {
            "baseUrl must include a host. baseUrl=$baseUrl"
        }
        require(baseUrl.userInfo == null) {
            "baseUrl must not include user info. Use aclToken for Consul ACL credentials."
        }
        require(baseUrl.query == null && baseUrl.fragment == null) {
            "baseUrl must not include query or fragment. baseUrl=$baseUrl"
        }
        datacenter?.let {
            require(it.isNotBlank()) { "datacenter must be null or non-blank." }
        }
        aclToken?.let {
            require(it.isNotBlank()) { "aclToken must be null or non-blank." }
        }
        require(requestTimeout > Duration.ZERO) {
            "requestTimeout must be positive. requestTimeout=$requestTimeout"
        }
    }

    val normalizedBaseUrl: URI =
        URI(
            baseUrl.scheme,
            null,
            baseUrl.host,
            baseUrl.port,
            baseUrl.path?.trimEnd('/')?.ifBlank { null },
            null,
            null,
        )

    override fun toString(): String =
        "ConsulEndpoint(" +
                "baseUrl=$normalizedBaseUrl, " +
                "datacenter=$datacenter, " +
                "aclToken=${aclToken?.let { "***" }}, " +
                "requestTimeout=$requestTimeout" +
                ")"

    companion object {
        val DefaultRequestTimeout: Duration = 5.seconds

        private const val serialVersionUID = 1L
    }
}
