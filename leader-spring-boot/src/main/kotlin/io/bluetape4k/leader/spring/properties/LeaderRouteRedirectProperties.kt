package io.bluetape4k.leader.spring.properties

import java.io.Serializable
import java.net.InetAddress
import java.time.Duration

/**
 * redirect-to-leader 응답을 opt-in으로 제어하는 설정입니다.
 *
 * host와 proxy 주소는 애플리케이션이 소유한 공개 mapping과 원시 trust 근거를
 * 표현합니다. 이 타입은 네트워크 조회나 forwarded header 해석을 수행하지 않습니다.
 * `enabled=true`일 때 host는 lowercase ASCII exact name/canonical IPv4만, proxy는
 * DNS/CIDR/zone 없이 numeric IPv4/IPv6만 허용합니다.
 */
data class LeaderRouteRedirectProperties(
    val enabled: Boolean = false,
    val allowedHosts: List<String> = emptyList(),
    val trustedProxyAddresses: List<String> = emptyList(),
    val leaseSafetyWindow: Duration = Duration.ZERO,
) : Serializable {

    /** startup policy wiring에서 enabled semantic validation과 정규화를 한 번 수행합니다. */
    internal fun normalized(): LeaderRouteRedirectNormalizedProperties {
        if (!enabled) {
            return LeaderRouteRedirectNormalizedProperties(
                enabled = false,
                allowedHosts = emptySet(),
                trustedProxyAddresses = emptySet(),
                leaseSafetyWindow = Duration.ZERO,
            )
        }
        require(leaseSafetyWindow.isZero || !leaseSafetyWindow.isNegative) {
            "lease-safety-window must be finite and non-negative"
        }
        allowedHosts.forEach(::validateAllowedHost)
        trustedProxyAddresses.forEach(::validateTrustedProxyAddress)
        return LeaderRouteRedirectNormalizedProperties(
            enabled = enabled,
            allowedHosts = allowedHosts.map(::normalizeHost).toSet(),
            trustedProxyAddresses = trustedProxyAddresses.map(::normalizeAddress).toSet(),
            leaseSafetyWindow = leaseSafetyWindow,
        )
    }

    companion object {
        private const val serialVersionUID = 1L

        private fun validateAllowedHost(value: String) {
            require(value.isNotBlank()) { "allowed-hosts must not contain blank values" }
            require(value.all { it.code in PRINTABLE_ASCII_MIN..PRINTABLE_ASCII_MAX }) {
                "allowed-hosts must contain printable ASCII hosts"
            }
            require(value == value.trim()) { "allowed-hosts must not contain surrounding whitespace" }
            require(!value.contains('*')) { "allowed-hosts must not contain wildcards" }
            require(!value.contains('/')) { "allowed-hosts must not contain CIDR or path syntax" }
            require(!value.contains(':')) { "allowed-hosts must not contain ports or IPv6 literals" }
            require(!value.contains('%')) { "allowed-hosts must not contain zones or encoded values" }
            require(!value.endsWith('.')) { "allowed-hosts must not have a trailing dot" }
            require(value.none(Char::isWhitespace)) {
                "allowed-hosts must not contain whitespace"
            }
            require(value.lowercase() == value) { "allowed-hosts must be lowercase ASCII" }
            require(isValidHostSyntax(value)) {
                "allowed-hosts must contain valid DNS names or canonical IPv4 addresses"
            }
        }

        private fun validateTrustedProxyAddress(value: String) {
            require(value.isNotBlank()) { "trusted-proxy-addresses must not contain blank values" }
            require(value == value.trim()) { "trusted-proxy-addresses must not contain surrounding whitespace" }
            require(!value.contains('/')) { "trusted-proxy-addresses must not contain CIDR syntax" }
            require(!value.contains('%')) { "trusted-proxy-addresses must not contain IPv6 zones" }
            require(!value.contains(':') || value.count { it == ':' } >= MIN_IPV6_COLONS) {
                "trusted-proxy-addresses must contain a numeric IPv4 or IPv6 address"
            }
            require(isNumericAddress(value)) {
                "trusted-proxy-addresses must contain numeric IPv4 or IPv6 addresses"
            }
            normalizeAddress(value)
        }

        private fun isNumericAddress(value: String): Boolean {
            return if (value.contains(':')) {
                value.all { it in "0123456789abcdefABCDEF:." }
            } else {
                val octets = value.split('.')
                octets.size == IPV4_OCTET_COUNT && octets.all { octet ->
                    octet.isNotEmpty() && (octet == "0" || !octet.startsWith('0')) &&
                        octet.all(Char::isDigit) && octet.toIntOrNull() in 0..IPV4_MAX_OCTET
                }
            }
        }

        internal fun normalizeHost(value: String): String = value.lowercase()

        internal fun isValidHostSyntax(value: String): Boolean = when {
            value.isEmpty() -> false
            !value.all(::isAsciiHostCharacter) -> false
            value.all { it.isDigit() || it == '.' } -> isCanonicalIpv4(value)
            else -> isValidDnsHost(value)
        }

        private fun isAsciiHostCharacter(value: Char): Boolean =
            value.code <= ASCII_MAX && !value.isWhitespace()

        private fun isValidDnsHost(value: String): Boolean =
            value.all { it.isLetterOrDigit() || it == '-' || it == '.' } &&
                value.split('.').all(::isValidDnsLabel)

        private fun isValidDnsLabel(value: String): Boolean =
            value.isNotEmpty() &&
                value.first().isLetterOrDigit() &&
                value.last().isLetterOrDigit() &&
                value.all { it.isLetterOrDigit() || it == '-' }

        private fun isCanonicalIpv4(value: String): Boolean {
            val octets = value.split('.')
            return octets.size == IPV4_OCTET_COUNT && octets.all { octet ->
                octet.isNotEmpty() &&
                    (octet == "0" || !octet.startsWith('0')) &&
                    octet.all(Char::isDigit) &&
                    octet.toIntOrNull() in 0..IPV4_MAX_OCTET
            }
        }

        internal fun normalizeAddress(value: String): String {
            require(isNumericAddress(value)) {
                "trusted-proxy-addresses must contain numeric IPv4 or IPv6 addresses"
            }
            val bytes = InetAddress.getByName(value).address
            return InetAddress.getByAddress(bytes).hostAddress.lowercase()
        }

        private const val ASCII_MAX = 0x7F
        private const val PRINTABLE_ASCII_MIN = 0x21
        private const val PRINTABLE_ASCII_MAX = 0x7E
        private const val MIN_IPV6_COLONS = 2
        private const val IPV4_OCTET_COUNT = 4
        private const val IPV4_MAX_OCTET = 255
    }
}

internal data class LeaderRouteRedirectNormalizedProperties(
    val enabled: Boolean,
    val allowedHosts: Set<String>,
    val trustedProxyAddresses: Set<String>,
    val leaseSafetyWindow: Duration,
)
