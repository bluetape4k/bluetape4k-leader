package io.bluetape4k.leader.utils

internal infix fun Long.safePlus(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
