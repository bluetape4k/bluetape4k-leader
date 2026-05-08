package io.bluetape4k.leader.hazelcast

import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HazelcastSuspendCancellationSafetyTest {

    @Test
    fun `suspend elector unlock failure handling rethrows CancellationException`() {
        val source = sourceText("HazelcastSuspendLeaderElector.kt")

        source.contains("catch (e: CancellationException) {\n                        throw e\n                    } catch (e: Exception)").shouldBeTrue()
    }

    @Test
    fun `suspend group elector unlock failure handling rethrows CancellationException`() {
        val source = sourceText("HazelcastSuspendLeaderGroupElector.kt")

        source.contains("catch (e: CancellationException) {\n                        throw e\n                    } catch (e: Exception)").shouldBeTrue()
    }

    private fun sourceText(fileName: String): String =
        Path.of("src/main/kotlin/io/bluetape4k/leader/hazelcast", fileName)
            .toFile()
            .readText()
}
