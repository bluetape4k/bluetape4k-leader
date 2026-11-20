package io.bluetape4k.leader.local

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class LocalLeaderPublicApiCompatibilityTest {

    @Test
    fun `abstract local companions retain Object as their direct superclass`() {
        AbstractLocalLeaderElector.Companion::class.java.superclass shouldBeEqualTo Any::class.java
        AbstractLocalLeaderGroupElector.Companion::class.java.superclass shouldBeEqualTo Any::class.java
    }
}
