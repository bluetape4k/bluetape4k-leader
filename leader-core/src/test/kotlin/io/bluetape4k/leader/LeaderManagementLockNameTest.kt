package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class LeaderManagementLockNameTest {

    companion object: KLogging()

    @Test
    fun `management lock name accepts the ASCII allow list`() {
        listOf(
            "a",
            "A0",
            "job-name",
            "job_name",
            "tenant:job.1",
            "a" + "b".repeat(127),
        ).forEach { lockName ->
            lockName.isManagementActionLockName().shouldBeTrue()
            lockName.requireManagementActionLockName() shouldBeEqualTo lockName
        }
    }

    @Test
    fun `management lock name rejects hostile selectors without throwing from boolean helper`() {
        listOf(
            "",
            "   ",
            ".",
            "..",
            ".hidden",
            "a/b",
            "a\\b",
            "a%b",
            "a*b",
            "a\u0000b",
            "éclair",
            "a" + "b".repeat(128),
        ).forEach { lockName ->
            lockName.isManagementActionLockName().shouldBeFalse()
            assertFailsWith<IllegalArgumentException> {
                lockName.requireManagementActionLockName()
            }
        }
    }

    @Test
    fun `기존 JVM facade 메서드 이름을 유지한다`() {
        val facade = Class.forName("io.bluetape4k.leader.LeaderManagementLockNameKt")

        facade.getDeclaredMethod("isManagementActionLockName", String::class.java).apply {
            Modifier.isStatic(modifiers).shouldBeTrue()
            returnType shouldBeEqualTo Boolean::class.javaPrimitiveType
        }
        facade.getDeclaredMethod("requireManagementActionLockName", String::class.java).apply {
            Modifier.isStatic(modifiers).shouldBeTrue()
            returnType shouldBeEqualTo String::class.java
        }
    }
}
