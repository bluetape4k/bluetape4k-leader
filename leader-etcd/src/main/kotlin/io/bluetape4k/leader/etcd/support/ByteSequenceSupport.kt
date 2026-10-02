package io.bluetape4k.leader.etcd.support

import io.etcd.jetcd.ByteSequence
import java.nio.charset.Charset

fun String.toByteSequence(cs: Charset = Charsets.UTF_8): ByteSequence =
    ByteSequence.from(this, cs)

fun ByteArray.toByteSequence(): ByteSequence =
    ByteSequence.from(this)

fun ByteSequence.toUtf8String(): String = toString(Charsets.UTF_8)
