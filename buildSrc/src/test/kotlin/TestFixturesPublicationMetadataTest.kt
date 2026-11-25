import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeNull
import org.gradle.testkit.runner.GradleRunner
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test

class TestFixturesPublicationMetadataTest {
    @Test
    fun `test fixtures capability stays in Gradle metadata without Maven warning`() {
        val repositoryRoot = repositoryRoot()
        val result = GradleRunner.create()
            .withProjectDir(repositoryRoot.toFile())
            .withArguments(
                "-Dorg.gradle.configuration-cache=false",
                "--rerun-tasks",
                "--warning-mode",
                "all",
                "--console",
                "plain",
                ":bluetape4k-leader-core:generatePomFileForBluetapeLeaderPublication",
                ":bluetape4k-leader-core:generateMetadataFileForBluetapeLeaderPublication",
            )
            .build()

        result.output shouldNotContain "Declares capability io.github.bluetape4k.leader:bluetape4k-leader-core-test-fixtures"

        val pom = repositoryRoot.resolve("leader-core/build/publications/BluetapeLeader/pom-default.xml")
            .takeIf { it.exists() }
            ?.readText()
            .shouldNotBeNull()
        pom shouldNotContain "testFixtures"

        val moduleMetadata = repositoryRoot.resolve("leader-core/build/publications/BluetapeLeader/module.json")
            .takeIf { it.exists() }
            ?.readText()
            .shouldNotBeNull()
        moduleMetadata shouldContain "\"name\": \"testFixturesApiElements\""
        moduleMetadata shouldContain "\"name\": \"testFixturesRuntimeElements\""
        moduleMetadata shouldContain "\"name\": \"bluetape4k-leader-core-test-fixtures\""
    }

    private fun repositoryRoot(): java.nio.file.Path {
        var current = Path(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (!current.resolve("settings.gradle.kts").exists()) {
            current = current.parent ?: error("Repository root was not found from $current")
        }
        return current
    }
}
