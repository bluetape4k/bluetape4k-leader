package io.bluetape4k.leader.internal

import io.bluetape4k.support.requireGe

// TODO(#50): promote to bluetape4k-support after v1 stabilizes (D1)

/**
 * Truncates this string to at most [maxBytes] UTF-8 encoded bytes.
 *
 * ## Behavior / Contract
 * - The truncation point is always aligned to a valid UTF-8 character boundary:
 *   continuation bytes (`0b10xxxxxx`) at the cut point are stepped back so the
 *   returned string never contains an incomplete multi-byte sequence.
 * - Surrogate-pair boundary safety: because the function operates on raw UTF-8
 *   bytes and then decodes back, it is safe for all BMP and supplementary
 *   code-points.  Grapheme cluster boundaries are **out of scope** — a multi-
 *   codepoint grapheme may be split if it crosses the byte limit.
 * - If the string already fits within [maxBytes] bytes, it is returned unchanged.
 * - [maxBytes] must be ≥ 0; negative values throw [IllegalArgumentException].
 *
 * ## Example
 * ```kotlin
 * "Hello, 世界".truncateUtf8(8)  // "Hello, " (7 bytes) — '世' (3 bytes) would overflow
 * "abc".truncateUtf8(100)        // "abc" — already within limit
 * ```
 */
internal fun String.truncateUtf8(maxBytes: Int): String {
    maxBytes.requireGe(0, "maxBytes")
    val bytes = toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return this

    var cutoff = maxBytes
    // Step back past any UTF-8 continuation bytes (10xxxxxx) to align to a character boundary.
    while (cutoff > 0 && (bytes[cutoff].toInt() and 0xC0) == 0x80) {
        cutoff--
    }
    return String(bytes, 0, cutoff, Charsets.UTF_8)
}
