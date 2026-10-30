package io.bluetape4k.leader.exposed.jdbc.lock

import org.junit.jupiter.api.Test

class ExposedJdbcLockNameApiCompatibilityTest {

    @Test
    fun `legacy validateExposedLockName JVM facade remains available`() {
        val facade = Class.forName("io.bluetape4k.leader.exposed.jdbc.lock.ExposedJdbcSchemaInitializerKt")
        val method = facade.getMethod("validateExposedLockName", String::class.java)

        method.invoke(null, "legacy-lock")
    }
}
