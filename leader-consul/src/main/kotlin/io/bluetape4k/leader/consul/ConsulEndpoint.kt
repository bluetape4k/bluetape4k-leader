package io.bluetape4k.leader.consul

import java.io.Serializable
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Caller-owned Consul HTTP endpoint configuration.
 *
 * ## Behavior / Contract
 * - The endpoint is a bluetape4k-owned DTO; no third-party Consul client type is part of the public API.
 * - [baseUrl] must point to a Consul HTTP API endpoint such as `http://localhost:8500`.
 * - [aclToken] is optional and is sent as the Consul ACL token when supplied.
 * - The elector creates internal HTTP requests from this value but does not own or mutate external Consul lifecycle.
 */
data class ConsulEndpoint(
    val baseUrl: URI,
    val datacenter: String? = null,
    val aclToken: String? = null,
    val requestTimeout: Duration = DefaultRequestTimeout,
) : Serializable {

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
