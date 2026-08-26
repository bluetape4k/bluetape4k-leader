package io.bluetape4k.leader.audit.http

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import java.net.URI

/**
 * caller가 allow-list와 DNS/SSRF trust를 확인했음을 표현하는 HTTPS endpoint wrapper입니다.
 *
 * 이 wrapper는 HTTPS URI 문법과 credential/query 경계를 검사하지만 hostname을 IP에
 * 고정하거나 DNS rebinding, private/link-local/ULA/CGNAT 주소를 차단하지 않습니다.
 * 그 trust 정책은 caller 또는 별도 egress proxy가 소유합니다.
 */
class LeaderAuditTrustedHttpsEndpoint private constructor(
    /** 검증된 HTTPS target URI입니다. */
    val uri: URI,
) {

    companion object {
        /** 명시적으로 신뢰한 HTTPS endpoint를 생성합니다. */
        @JvmStatic
        fun trusted(uri: URI): LeaderAuditTrustedHttpsEndpoint {
            val scheme = uri.scheme.requireNotNull("uri.scheme")
            scheme.requireNotBlank("uri.scheme")
            require(scheme.equals("https", ignoreCase = true)) {
                "uri.scheme must be https"
            }
            require(uri.isAbsolute && !uri.isOpaque) {
                "uri must be an absolute hierarchical URI"
            }
            uri.host.requireNotBlank("uri.host")
            require(uri.userInfo == null) { "uri must not contain user-info" }
            require(uri.query == null) { "uri must not contain a query" }
            require(uri.fragment == null) { "uri must not contain a fragment" }
            require(!uri.toString().containsHttpControlCharacter()) {
                "uri must not contain control characters"
            }
            return LeaderAuditTrustedHttpsEndpoint(uri)
        }
    }
}

private const val HTTP_CONTROL_CHARACTER_MIN_CODE: Int = 0x20
private const val HTTP_DELETE_CHARACTER_CODE: Int = 0x7f

private fun String.containsHttpControlCharacter(): Boolean = any {
    it.code < HTTP_CONTROL_CHARACTER_MIN_CODE || it.code == HTTP_DELETE_CHARACTER_CODE
}
