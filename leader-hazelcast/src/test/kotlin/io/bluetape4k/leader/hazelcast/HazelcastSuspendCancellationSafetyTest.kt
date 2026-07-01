package io.bluetape4k.leader.hazelcast

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HazelcastSuspendCancellationSafetyTest {

    @Test
    fun `suspend elector unlock failure handling rethrows CancellationException`() {
        val source = sourceText("HazelcastSuspendLeaderElector.kt")

        source.rethrowsCancellationBeforeBroadCatch().shouldBeTrue()
    }

    @Test
    fun `suspend group elector unlock failure handling rethrows CancellationException`() {
        val source = sourceText("HazelcastSuspendLeaderGroupElector.kt")

        source.rethrowsCancellationBeforeBroadCatch().shouldBeTrue()
    }

    private fun sourceText(fileName: String): String =
        Path.of("src/main/kotlin/io/bluetape4k/leader/hazelcast", fileName)
            .toFile()
            .readText()

    private fun String.rethrowsCancellationBeforeBroadCatch(): Boolean {
        val cancellationCatch = indexOf("catch (e: CancellationException) {\n                    throw e\n                }")
        val broadCatch = indexOf("catch (e: Exception)", startIndex = cancellationCatch.coerceAtLeast(0))
        return cancellationCatch >= 0 && broadCatch > cancellationCatch
    }
}
