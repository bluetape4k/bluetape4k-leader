import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension

/**
 * Project property 또는 환경 변수에서 값을 조회합니다.
 */
fun Project.getEnvOrProperty(propertyKey: String, envKey: String): String =
    findProperty(propertyKey) as? String ?: System.getenv(envKey).orEmpty()

data class CentralPublishingConfig(
    val username: String,
    val password: String,
)

/**
 * Central Portal 자격증명을 project property / 환경 변수에서 로딩합니다.
 *
 * Property keys: `central.user`, `central.password`
 * Env var keys:  `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`
 */
fun Project.resolveCentralPublishingConfig(): CentralPublishingConfig = CentralPublishingConfig(
    username = getEnvOrProperty("central.user", "CENTRAL_USERNAME")
        .ifBlank { getEnvOrProperty("centralPortalUsername", "CENTRAL_USERNAME") },
    password = getEnvOrProperty("central.password", "CENTRAL_PASSWORD")
        .ifBlank { getEnvOrProperty("centralPortalPassword", "CENTRAL_PASSWORD") },
)

data class SigningConfig(
    val keyId: String,
    val key: String,
    val password: String,
    val useGpgCmd: Boolean,
    val gpgExecutable: String,
    val gpgKeyName: String,
)

/**
 * Signing 설정을 project property / 환경 변수에서 로딩합니다.
 *
 * Env var keys: `SIGNING_KEY_ID`, `SIGNING_KEY`, `SIGNING_PASSWORD`
 */
fun Project.resolveSigningConfig(): SigningConfig {
    val keyId = getEnvOrProperty("signingKeyId", "SIGNING_KEY_ID")
    val key = getEnvOrProperty("signingKey", "SIGNING_KEY").replace("\\n", "\n")
    val password = getEnvOrProperty("signingPassword", "SIGNING_PASSWORD")
    val useGpgCmd = getEnvOrProperty("signingUseGpgCmd", "SIGNING_USE_GPG_CMD").toBoolean()
    val gpgExecutable = getEnvOrProperty("signing.gnupg.executable", "GPG_EXECUTABLE")
        .ifBlank { "/opt/homebrew/bin/gpg" }
    val gpgKeyName = getEnvOrProperty("signing.gnupg.keyName", "GPG_KEY_NAME").ifBlank { keyId }
    return SigningConfig(keyId, key, password, useGpgCmd, gpgExecutable, gpgKeyName)
}

/**
 * Maven publication 서명을 설정합니다.
 * - CI: `SIGNING_KEY` + `SIGNING_PASSWORD` 환경 변수로 in-memory PGP 서명
 * - 로컬: `signingUseGpgCmd=true` 또는 gpg-cmd 설정으로 서명
 */
fun Project.configurePublishingSigning(publicationName: String) {
    val config = resolveSigningConfig()
    extensions.configure<SigningExtension> {
        when {
            config.key.isNotBlank() && config.password.isNotBlank() -> {
                useInMemoryPgpKeys(config.keyId.ifBlank { null }, config.key, config.password)
                val publishing = project.extensions.findByType(PublishingExtension::class.java)
                publishing?.publications?.findByName(publicationName)?.let { sign(it) }
            }
            config.useGpgCmd -> {
                if (file(config.gpgExecutable).exists()) {
                    project.extensions.extraProperties["signing.gnupg.executable"] = config.gpgExecutable
                }
                if (config.gpgKeyName.isNotBlank()) {
                    project.extensions.extraProperties["signing.gnupg.keyName"] = config.gpgKeyName
                }
                useGpgCmd()
                val publishing = project.extensions.findByType(PublishingExtension::class.java)
                publishing?.publications?.findByName(publicationName)?.let { sign(it) }
            }
            else -> {
                // 서명 키 없음 — 로컬 개발 빌드에서는 서명 건너뜀
            }
        }
    }
}
