package io.bluetape4k.leader.history

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class LeaderHistoryKeyContractTest {

    @Test
    fun `id retains the Long JVM descriptors`() {
        val type = LeaderHistoryKey::class.java
        val boxedLong = java.lang.Long::class.java

        type.getDeclaredMethod("getId").returnType shouldBeEqualTo boxedLong
        type.getDeclaredMethod("component1").returnType shouldBeEqualTo boxedLong
        type.getDeclaredMethod(
            "copy",
            boxedLong,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        ).returnType shouldBeEqualTo type
    }
}
