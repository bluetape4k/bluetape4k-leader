import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestcontainersReusePolicyTest {

    private val repositoryRoot: Path = Path.of("..").toAbsolutePath().normalize()

    @Test
    fun `tests and examples never enable container reuse implicitly`() {
        val kotlinSources = kotlinSourcesUnder(repositoryRoot)
            .filter { !it.toString().contains("/buildSrc/") }
            .filter { it.toString().contains("/src/test/") || it.toString().contains("/examples/") }

        val explicitReuseViolations = kotlinSources.flatMap { source ->
            source.readLinesWithNumbers()
                .filter { (_, line) -> EXPLICIT_REUSE.containsMatchIn(line) }
                .map { (lineNumber, line) -> "${repositoryRoot.relativize(source)}:$lineNumber: ${line.trim()}" }
        }
        val exampleLauncherViolations = kotlinSources
            .filter { it.toString().contains("/examples/") && it.toString().contains("/src/main/") }
            .flatMap { source ->
                source.readLinesWithNumbers()
                    .filter { (_, line) -> CONTAINER_LAUNCHER.containsMatchIn(line) }
                    .map { (lineNumber, line) -> "${repositoryRoot.relativize(source)}:$lineNumber: ${line.trim()}" }
            }
        val violations = explicitReuseViolations + exampleLauncherViolations

        assertTrue(violations.isEmpty(), violations.joinToString(prefix = "Implicit reuse found:\n", separator = "\n"))
    }

    @Test
    fun `developer local reuse is explicit and disabled in CI`() {
        val exampleMainSources = kotlinSourcesUnder(repositoryRoot.resolve("examples"))
            .filter { it.toString().contains("/src/main/") }
        val optInSources = exampleMainSources.filter { source ->
            source.toFile().readText().contains("environmentSupportsReuse()")
        }

        assertEquals(9, optInSources.size, "Every Testcontainers-backed example main must declare the local opt-in")
        optInSources.forEach { source ->
            val text = source.toFile().readText()
            assertTrue(
                text.contains("System.getenv(\"CI\") != \"true\""),
                "${repositoryRoot.relativize(source)} must disable reuse in CI",
            )
        }
    }

    private fun kotlinSourcesUnder(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }.toList()
        }

    private fun Path.readLinesWithNumbers(): List<Pair<Int, String>> =
        toFile().readLines().mapIndexed { index, line -> index + 1 to line }

    companion object {
        private val EXPLICIT_REUSE = Regex("reuse\\s*=\\s*true|withReuse\\(true\\)")
        private val CONTAINER_LAUNCHER = Regex(
            "Launcher\\.(redis|hazelcast|consul|dynamoDb|etcd|zookeeper|mongoDB|postgres|mysql|k3s)\\b",
        )
    }
}
