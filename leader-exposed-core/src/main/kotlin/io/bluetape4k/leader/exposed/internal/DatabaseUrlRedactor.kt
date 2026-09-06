package io.bluetape4k.leader.exposed.internal

import io.bluetape4k.leader.identity.LeaderInternalApi
import java.net.URI
import java.net.URISyntaxException

private const val INVALID_DATABASE_URL = "<invalid-database-url>"
private const val REDACTED_USER_INFO = "***"
private const val REDACTED_OPAQUE_URL = "<redacted>"

/**
 * 로그에 기록할 database URL에서 credential을 제거합니다.
 *
 * JDBC/R2DBC wrapper 접두사는 보존하되 userinfo를 마스킹하고 query, semicolon property,
 * fragment를 제거합니다. URL을 안전하게 해석할 수 없으면 원문 대신 고정 placeholder를 반환합니다.
 */
@LeaderInternalApi
@JvmSynthetic
fun redactDatabaseUrlForLog(url: String): String {
    val (wrapperPrefix, databaseUrl) = unwrapDatabaseUrl(url)
        ?: return INVALID_DATABASE_URL

    return try {
        val uri = URI(databaseUrl.substringBefore(';'))
        val redacted = if (uri.isOpaque) {
            redactOpaqueUri(uri)
        } else {
            redactHierarchicalUri(uri)
        }
        wrapperPrefix + redacted
    } catch (_: URISyntaxException) {
        INVALID_DATABASE_URL
    } catch (_: IllegalArgumentException) {
        INVALID_DATABASE_URL
    }
}

private fun unwrapDatabaseUrl(url: String): Pair<String, String>? {
    val unwrapped = when {
        url.isBlank() -> null
        url.startsWith("jdbc:", ignoreCase = true) -> url.take(5) to url.substring(5)
        url.startsWith("r2dbc:", ignoreCase = true) -> url.take(6) to url.substring(6)
        "://" in url -> "" to url
        else -> null
    }
    return unwrapped?.takeIf { (_, databaseUrl) -> databaseUrl.isNotBlank() }
}

private fun redactHierarchicalUri(uri: URI): String {
    require(!uri.scheme.isNullOrBlank()) { "Database URL scheme is required." }
    require(uri.rawAuthority == null || uri.host != null) { "Database URL host is invalid." }

    val redactedUserInfo = uri.rawUserInfo
        ?.takeUnless(String::isEmpty)
        ?.let { REDACTED_USER_INFO }
    val redactedPath = uri.path?.substringBefore(';')

    return URI(
        uri.scheme,
        redactedUserInfo,
        uri.host,
        uri.port,
        redactedPath,
        null,
        null,
    ).toString()
}

private fun redactOpaqueUri(uri: URI): String {
    require(!uri.scheme.isNullOrBlank()) { "Database URL scheme is required." }

    uri.rawSchemeSpecificPart
        ?.takeUnless(String::isBlank)
        ?: throw IllegalArgumentException("Database URL scheme-specific part is required.")

    return "${uri.scheme}:$REDACTED_OPAQUE_URL"
}
