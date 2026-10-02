package io.bluetape4k.leader.examples.support

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class ExampleTestcontainersTest {

    companion object: KLogging()

    @Test
    fun `CI marker presence denies reuse regardless of marker value`() {
        developerLocalReuseEnabled(mapOf("CI" to "1")) { true }.shouldBeFalse()
        developerLocalReuseEnabled(mapOf("CI" to "false")) { true }.shouldBeFalse()
    }

    @Test
    fun `GitHub Actions marker presence denies reuse`() {
        developerLocalReuseEnabled(mapOf("GITHUB_ACTIONS" to "1")) { true }.shouldBeFalse()
    }

    @Test
    fun `developer opt-in enables reuse without CI markers`() {
        developerLocalReuseEnabled(emptyMap()) { true }.shouldBeTrue()
        developerLocalReuseEnabled(emptyMap()) { false }.shouldBeFalse()
    }

    @Test
    fun `reusable container starts without shutdown registration`() {
        val events = mutableListOf<String>()

        startExampleContainer(
            reuse = true,
            createContainer = { reuse -> FakeContainer(reuse, events) },
            start = { events += "start" },
            registerForShutdown = { events += "register" },
        )

        events shouldBeEqualTo listOf("start")
    }

    @Test
    fun `non-reusable container starts with shutdown registration`() {
        val events = mutableListOf<String>()

        startExampleContainer(
            reuse = false,
            createContainer = { reuse -> FakeContainer(reuse, events) },
            start = { events += "start" },
            registerForShutdown = { events += "register" },
        )

        events shouldBeEqualTo listOf("start", "register")
    }

    private class FakeContainer(
        val reuse: Boolean,
        val events: MutableList<String>,
    )
}
