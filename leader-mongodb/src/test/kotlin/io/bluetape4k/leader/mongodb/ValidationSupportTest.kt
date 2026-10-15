package io.bluetape4k.leader.mongodb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class ValidationSupportTest {

    @Test
    fun `validateMonoLockName includes the supplied parameter name`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            "resource:slot:0".validateMonoLockName("resourceName")
        }

        exception.message shouldBeEqualTo "resourceName must not contain ':slot:': resource:slot:0"
    }
}
