import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.gradle.testfixtures.ProjectBuilder

class PublishingSigningSupportTest {
    @Test
    fun `resolves escaped and base64 private key armor`() {
        val armor = "-----BEGIN PGP PRIVATE KEY BLOCK-----\\nkey-body\\n-----END PGP PRIVATE KEY BLOCK-----"
        val expected = armor.replace("\\n", "\n")

        assertEquals(expected, io.bluetape4k.gradle.resolveSigningKey(armor))
        assertEquals(expected, io.bluetape4k.gradle.resolveSigningKey(Base64.getEncoder().encodeToString(armor.toByteArray())))
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
}
