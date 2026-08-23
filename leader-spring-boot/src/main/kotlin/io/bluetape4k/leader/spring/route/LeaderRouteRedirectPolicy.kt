package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteRedirectNormalizedProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteRedirectProperties
import java.net.URI

internal class LeaderRouteRedirectPolicy(
    properties: LeaderRouteRedirectProperties,
    private val failureObserver: ((LeaderRouteRedirectFailureReason, LeaderRouteRedirectFramework) -> Unit)? = null,
) {
    private val normalized: LeaderRouteRedirectNormalizedProperties = properties.normalized()

    fun redirect(
        slot: LeaderSlot,
        evaluation: LeaderRouteEvaluation,
        resolver: LeaderRouteRedirectResolver?,
        metadata: LeaderRouteRedirectRequestMetadata?,
        framework: LeaderRouteRedirectFramework,
    ): URI? = if (!normalized.enabled || resolver == null) {
        null
    } else {
        when (evaluation.decision) {
            LeaderRouteDecision.Allowed -> null
            LeaderRouteDecision.Unavailable -> {
                observe(LeaderRouteRedirectFailureReason.UNAVAILABLE, framework)
                null
            }

            LeaderRouteDecision.NotLeader -> evaluateNotLeader(slot, evaluation, resolver, metadata, framework)
        }
    }

    private fun evaluateNotLeader(
        slot: LeaderSlot,
        evaluation: LeaderRouteEvaluation,
        resolver: LeaderRouteRedirectResolver,
        metadata: LeaderRouteRedirectRequestMetadata?,
        framework: LeaderRouteRedirectFramework,
    ): URI? {
        val deadline = runCatching {
            evaluation.evaluatedAt.plus(normalized.leaseSafetyWindow)
        }.getOrNull()
        val state = evaluation.leaderState
        val stale = state != null && !hasFreshLease(state, deadline)
        val target = if (!stale) {
            resolveTarget(slot, state, evaluation.evaluatedAt, resolver, framework)
        } else {
            null
        }
        return when {
            stale -> {
                observe(LeaderRouteRedirectFailureReason.STALE_LEASE, framework)
                null
            }

            target == null -> null
            !isMetadataTrusted(target, metadata, framework) -> null
            !LeaderRouteRedirectUriValidator.isSafe(target, normalized.allowedHosts) -> {
                observe(LeaderRouteRedirectFailureReason.URI_REJECTED, framework)
                null
            }

            else -> target
        }
    }

    private fun hasFreshLease(
        state: io.bluetape4k.leader.LeaderState,
        deadline: java.time.Instant?,
    ): Boolean = deadline != null && state.leader?.leaseUntil?.isAfter(deadline) == true

    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun resolveTarget(
        slot: LeaderSlot,
        state: io.bluetape4k.leader.LeaderState?,
        evaluatedAt: java.time.Instant,
        resolver: LeaderRouteRedirectResolver,
        framework: LeaderRouteRedirectFramework,
    ): URI? {
        val target = try {
            resolver.resolve(
                LeaderRouteRedirectContext(slot, state, evaluatedAt, normalized.leaseSafetyWindow),
            )
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            observe(LeaderRouteRedirectFailureReason.CALLBACK_FAILURE, framework)
            return null
        }
        if (target == null) observe(LeaderRouteRedirectFailureReason.NULL_TARGET, framework)
        return target
    }

    private fun isMetadataTrusted(
        target: URI,
        metadata: LeaderRouteRedirectRequestMetadata?,
        framework: LeaderRouteRedirectFramework,
    ): Boolean {
        return when (metadata?.forwardedHeadersPresent) {
            true -> {
                val peer = metadata.transportPeerAddress
                val trusted = peer != null && runCatching {
                    LeaderRouteRedirectProperties.normalizeAddress(peer) in normalized.trustedProxyAddresses
                }.getOrDefault(false)
                if (!trusted) observe(LeaderRouteRedirectFailureReason.UNTRUSTED_PROXY, framework)
                trusted
            }

            false -> true
            null -> {
                if (target.isAbsolute) observe(LeaderRouteRedirectFailureReason.METADATA_UNKNOWN, framework)
                !target.isAbsolute
            }
        }
    }

    private fun observe(reason: LeaderRouteRedirectFailureReason, framework: LeaderRouteRedirectFramework) {
        try {
            failureObserver?.invoke(reason, framework)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            // Failure observation is best effort; it must not change the redirect decision.
        }
    }
}

internal object LeaderRouteRedirectUriValidator {

    fun isSafe(uri: URI, allowedHosts: Set<String>): Boolean =
        !hasUnsafeRawComponent(uri) &&
            if (uri.isAbsolute) isSafeAbsolute(uri, allowedHosts) else isSafeRelative(uri)

    private fun hasUnsafeRawComponent(uri: URI): Boolean {
        val raw = uri.toString()
        return hasUnsafeRawText(raw) || containsEncodedControl(raw) || hasUnsafeUriComponent(uri)
    }

    private fun hasUnsafeRawText(raw: String): Boolean =
        raw.any { it.code <= ASCII_CONTROL_MAX || it.code == ASCII_DELETE } ||
            raw.contains("%5c", ignoreCase = true) ||
            raw.contains('\\')

    private fun hasUnsafeUriComponent(uri: URI): Boolean =
        uri.rawFragment != null || uri.isOpaque

    private fun isSafeRelative(uri: URI): Boolean =
        uri.rawPath?.let { path ->
            path.startsWith('/') && !path.startsWith("//")
        } == true && uri.scheme == null && uri.rawAuthority == null

    private fun isSafeAbsolute(uri: URI, allowedHosts: Set<String>): Boolean {
        val host = uri.host ?: return false
        val structureSafe = isSafeSchemeAndPort(uri) && LeaderRouteRedirectProperties.isValidHostSyntax(host)
        return structureSafe && isSafeAuthority(uri) && host.lowercase() in allowedHosts
    }

    private fun isSafeSchemeAndPort(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.port == NO_EXPLICIT_PORT

    private fun isSafeAuthority(uri: URI): Boolean {
        val authority = uri.rawAuthority ?: return false
        return !authority.endsWith(':') &&
            !authority.contains('@') &&
            !authority.contains("%40", ignoreCase = true) &&
            !authority.contains("%5c", ignoreCase = true)
    }

    private fun containsEncodedControl(value: String): Boolean {
        var index = 0
        var found = false
        while (!found && index + ENCODED_BYTE_LENGTH <= value.length) {
            if (value[index] == '%') {
                val code = value.substring(index + 1, index + ENCODED_BYTE_LENGTH).toIntOrNull(HEX_RADIX)
                found = code != null && (code <= ASCII_CONTROL_MAX || code == ASCII_DELETE)
                index += ENCODED_BYTE_LENGTH
            } else {
                index++
            }
        }
        return found
    }

    private const val ASCII_CONTROL_MAX = 0x1F
    private const val ASCII_DELETE = 0x7F
    private const val ENCODED_BYTE_LENGTH = 3
    private const val HEX_RADIX = 16
    private const val NO_EXPLICIT_PORT = -1
}
