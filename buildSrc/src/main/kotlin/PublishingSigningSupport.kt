import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension

/**
 * `Project` 호출은 benchmark/build support 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun Project.getEnvOrProperty(propertyKey: String, envKey: String): String =
    findProperty(propertyKey) as? String ?: System.getenv(envKey).orEmpty()

/**
 * `CentralPublishingConfig`는 benchmark/build support에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property username benchmark/build support 계약에서 `username` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property password benchmark/build support 계약에서 `password` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class CentralPublishingConfig(
    val username: String,
    val password: String,
)

/**
 * `Project` 호출은 benchmark/build support 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun Project.resolveCentralPublishingConfig(): CentralPublishingConfig = CentralPublishingConfig(
    username = getEnvOrProperty("central.user", "CENTRAL_USERNAME")
        .ifBlank { getEnvOrProperty("centralPortalUsername", "CENTRAL_USERNAME") },
    password = getEnvOrProperty("central.password", "CENTRAL_PASSWORD")
        .ifBlank { getEnvOrProperty("centralPortalPassword", "CENTRAL_PASSWORD") },
)

/**
 * `SigningConfig`는 benchmark/build support에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property keyId benchmark/build support 계약에서 `keyId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property key benchmark/build support 계약에서 `key` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property password benchmark/build support 계약에서 `password` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property useGpgCmd benchmark/build support 계약에서 `useGpgCmd` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property gpgExecutable benchmark/build support 계약에서 `gpgExecutable` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property gpgKeyName benchmark/build support 계약에서 `gpgKeyName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class SigningConfig(
    val keyId: String,
    val key: String,
    val password: String,
    val useGpgCmd: Boolean,
    val gpgExecutable: String,
    val gpgKeyName: String,
)

/**
 * `Project` 호출은 benchmark/build support 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
 * `Project` 호출은 benchmark/build support 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
