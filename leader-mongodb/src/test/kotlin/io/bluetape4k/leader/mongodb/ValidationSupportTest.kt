package io.bluetape4k.leader.mongodb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class ValidationSupportTest {

    companion object: KLogging()

    @Test
    fun `validateMonoLockName includes the supplied parameter name`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            "resource:slot:0".validateMongoLockName("resourceName")
        }

        log.debug { "exception=$exception" }
        exception.message shouldBeEqualTo "resourceName must not contain ':slot:': resource:slot:0"
    }
}
