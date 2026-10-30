package io.bluetape4k.leader.consul.internal

import java.net.URLEncoder

internal object ConsulKeyEncoder {

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8)
            .replace("+", "%20")
}
