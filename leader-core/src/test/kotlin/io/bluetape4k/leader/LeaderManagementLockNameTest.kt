package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

class LeaderManagementLockNameTest {

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
            isManagementActionLockName(lockName).shouldBeTrue()
            requireManagementActionLockName(lockName) shouldBeEqualTo lockName
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
            isManagementActionLockName(lockName).shouldBeFalse()
            assertFailsWith<IllegalArgumentException> {
                requireManagementActionLockName(lockName)
            }
        }
    }
}
