package io.bluetape4k.leader.identity

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class LeaderIdResolutionExceptionCompatibilityTest {

    @Test
    fun `기존 RuntimeException superclass를 유지한다`() {
        LeaderIdResolutionException::class.java.superclass shouldBeEqualTo RuntimeException::class.java
    }
}
