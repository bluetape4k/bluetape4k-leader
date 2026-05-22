package io.bluetape4k.leader.consul.internal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object ConsulKeyEncoder {

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8)
            .replace("+", "%20")
}
