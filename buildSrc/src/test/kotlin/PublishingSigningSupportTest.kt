import org.gradle.testfixtures.ProjectBuilder
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import javax.xml.parsers.DocumentBuilderFactory

class PublishingSigningSupportTest {
    @Test
    fun `resolves escaped and base64 private key armor`() {
        val armor = "-----BEGIN PGP PRIVATE KEY BLOCK-----\\nkey-body\\n-----END PGP PRIVATE KEY BLOCK-----"
        val expected = armor.replace("\\n", "\n")

        assertEquals(expected, io.bluetape4k.gradle.resolveSigningKey(armor))
        assertEquals(
            expected,
            io.bluetape4k.gradle.resolveSigningKey(Base64.getEncoder().encodeToString(armor.toByteArray()))
        )
    }

    @Test
    fun `normalizes prefixed long key id without leaking raw input`() {
        val raw = "0x1234567890ABCDEF"

        val normalized = io.bluetape4k.gradle.normalizeSigningKeyId(raw)

        assertEquals("0x90ABCDEF", normalized.value)
        assertNotNull(normalized.warning)
        assertFalse(normalized.warning.orEmpty().contains(raw))
    }

    @Test
    fun `uses normalized key id as blank gpg key name fallback`() {
        val project = ProjectBuilder.builder().build()
        project.extensions.extraProperties["signingKeyId"] = "0x1234567890ABCDEF"
        project.extensions.extraProperties["signingKey"] = ""
        project.extensions.extraProperties["signingPassword"] = ""
        project.extensions.extraProperties["signingUseGpgCmd"] = "true"
        project.extensions.extraProperties["signing.gnupg.keyName"] = ""

        val config = project.resolveSigningConfig()

        assertEquals("0x90ABCDEF", config.keyId)
        assertEquals("0x90ABCDEF", config.gpgKeyName)
    }

    @Test
    fun `published POM removes identical managed dependencies`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")

        val publication = project.extensions.getByType(PublishingExtension::class.java)
            .publications.create("test", MavenPublication::class.java)
        publication.groupId = "io.github.bluetape4k.test"
        publication.artifactId = "pom-deduplication-test"
        publication.version = "1.0.0"
        publication.pom.withXml {
            val dependencies = asNode()
                .appendNode("dependencyManagement")
                .appendNode("dependencies")
            repeat(2) {
                val dependency = dependencies.appendNode("dependency")
                dependency.appendNode("groupId", "org.example")
                dependency.appendNode("artifactId", "managed-library")
                dependency.appendNode("version", "1.2.3")
            }
        }

        project.configurePublishingSigning("test")

        val output = project.file("build/test-pom.xml")
        val task = project.tasks.getByName("generatePomFileForTestPublication") as GenerateMavenPom
        task.destination = output
        task.actions.forEach { action -> action.execute(task) }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output)
        val artifactIds = document.getElementsByTagName("artifactId")
        val matchingArtifacts = (0 until artifactIds.length).count { index ->
            artifactIds.item(index).textContent == "managed-library"
        }
        assertEquals(1, matchingArtifacts)
    }

    @Test
    fun `published POM treats reordered managed dependency fields as identical`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")

        val publication = project.extensions.getByType(PublishingExtension::class.java)
            .publications.create("test", MavenPublication::class.java)
        publication.groupId = "io.github.bluetape4k.test"
        publication.artifactId = "pom-field-order-test"
        publication.version = "1.0.0"
        publication.pom.withXml {
            val dependencies = asNode()
                .appendNode("dependencyManagement")
                .appendNode("dependencies")
            val first = dependencies.appendNode("dependency")
            first.appendNode("groupId", "software.amazon.awssdk")
            first.appendNode("artifactId", "bom")
            first.appendNode("version", "2.54.12")
            first.appendNode("type", "pom")
            first.appendNode("scope", "import")

            val second = dependencies.appendNode("dependency")
            second.appendNode("groupId", "software.amazon.awssdk")
            second.appendNode("artifactId", "bom")
            second.appendNode("version", "2.54.12")
            second.appendNode("scope", "import")
            second.appendNode("type", "pom")
        }
        project.configurePublishingSigning("test")

        val output = project.file("build/test-pom.xml")
        val task = project.tasks.getByName("generatePomFileForTestPublication") as GenerateMavenPom
        task.destination = output
        task.actions.forEach { action -> action.execute(task) }

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output)
        assertEquals(1, document.getElementsByTagName("dependency").length)
    }

    @Test
    fun `rejects conflicting managed dependency versions`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")

        val publication = project.extensions.getByType(PublishingExtension::class.java)
            .publications.create("test", MavenPublication::class.java)
        publication.groupId = "io.github.bluetape4k.test"
        publication.artifactId = "pom-conflict-test"
        publication.version = "1.0.0"
        publication.pom.withXml {
            val dependencies = asNode()
                .appendNode("dependencyManagement")
                .appendNode("dependencies")
            listOf("1.2.3", "2.0.0").forEach { version ->
                val dependency = dependencies.appendNode("dependency")
                dependency.appendNode("groupId", "org.example")
                dependency.appendNode("artifactId", "managed-library")
                dependency.appendNode("version", version)
            }
        }
        project.configurePublishingSigning("test")

        val task = project.tasks.getByName("generatePomFileForTestPublication") as GenerateMavenPom
        task.destination = project.file("build/test-pom.xml")
        val error = assertFailsWith<GradleException> {
            task.actions.forEach { action -> action.execute(task) }
        }

        assertTrue(error.message.orEmpty().contains("org.example:managed-library:jar:"))
    }
}
