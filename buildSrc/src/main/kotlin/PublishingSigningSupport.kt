import io.bluetape4k.gradle.NormalizedSigningKeyId
import io.bluetape4k.gradle.normalizeSigningKeyId
import io.bluetape4k.gradle.resolveSigningKey
import io.bluetape4k.gradle.resolveSigningKeyId
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.kotlin.dsl.configure
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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
    val normalizedKeyId: NormalizedSigningKeyId =
        normalizeSigningKeyId(getEnvOrProperty("signingKeyId", "SIGNING_KEY_ID"))
    val keyId = resolveSigningKeyId(normalizedKeyId.value)
    normalizedKeyId.warning?.let(project.logger::warn)
    val key = resolveSigningKey(getEnvOrProperty("signingKey", "SIGNING_KEY"))
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
    tasks.withType(GenerateMavenPom::class.java).configureEach {
        doLast {
            normalizeManagedDependencies(destination)
        }
    }
    extensions.configure<SigningExtension> {
        when {
            config.key.isNotBlank() && config.password.isNotBlank() -> {
                useInMemoryPgpKeys(config.keyId.ifBlank { null }, config.key, config.password)
                project.extensions.findByType(PublishingExtension::class.java)
                    ?.publications
                    ?.findByName(publicationName)
                    ?.let { sign(it) }
            }
            config.useGpgCmd -> {
                if (file(config.gpgExecutable).exists()) {
                    project.extensions.extraProperties["signing.gnupg.executable"] = config.gpgExecutable
                }
                if (config.gpgKeyName.isNotBlank()) {
                    project.extensions.extraProperties["signing.gnupg.keyName"] = config.gpgKeyName
                }
                useGpgCmd()
                project.extensions.findByType(PublishingExtension::class.java)
                    ?.publications
                    ?.findByName(publicationName)
                    ?.let { sign(it) }
            }
            else -> {
                // 서명 키 없음 — 로컬 개발 빌드에서는 서명 건너뜀
            }
        }
    }
}

internal fun normalizeManagedDependencies(pomFile: File) {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = factory.newDocumentBuilder().parse(pomFile)
    val dependencies = document.documentElement
        .childElements("dependencyManagement")
        .firstOrNull()
        ?.childElements("dependencies")
        ?.firstOrNull() ?: return
    val fingerprints = mutableMapOf<String, String>()
    var changed = false

    dependencies.childElements("dependency").forEach { dependency ->
        val groupId = dependency.childText("groupId")
        val artifactId = dependency.childText("artifactId")
        if (groupId.isBlank() || artifactId.isBlank()) return@forEach

        val type = dependency.childText("type").ifBlank { "jar" }
        val classifier = dependency.childText("classifier")
        val key = "$groupId:$artifactId:$type:$classifier"
        val fingerprint = dependency.canonicalFingerprint()
        val previous = fingerprints.putIfAbsent(key, fingerprint)

        when {
            previous == null -> Unit
            previous == fingerprint -> {
                dependencies.removeChild(dependency)
                changed = true
            }
            else -> throw GradleException(
                "Maven POM contains conflicting dependencyManagement entries for $key",
            )
        }
    }

    if (changed) {
        val output = java.io.ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        transformer.transform(DOMSource(document), StreamResult(output))
        pomFile.writeBytes(output.toByteArray())
    }
}

private fun Element.childElements(name: String): List<Element> =
    (0 until childNodes.length).mapNotNull { index ->
        (childNodes.item(index) as? Element)?.takeIf { it.tagName == name }
    }

private fun Element.childText(name: String): String = childElements(name).firstOrNull()?.textContent?.trim().orEmpty()

private fun org.w3c.dom.Node.canonicalFingerprint(): String = when (nodeType) {
    org.w3c.dom.Node.ELEMENT_NODE -> {
        val attributes = getAttributes()
        val serializedAttributes = (0 until attributes.length)
            .map { attributes.item(it) }
            .sortedBy { it.nodeName }
            .joinToString("|") { "${it.nodeName}=${it.nodeValue}" }
        val content = (0 until childNodes.length).mapNotNull { index ->
            val child = childNodes.item(index)
            when (child.nodeType) {
                org.w3c.dom.Node.ELEMENT_NODE -> child.canonicalFingerprint()
                org.w3c.dom.Node.TEXT_NODE,
                org.w3c.dom.Node.CDATA_SECTION_NODE,
                -> child.nodeValue?.trim()?.takeIf(String::isNotEmpty)
                else -> null
            }
        }.joinToString("|")
        "$nodeName[$serializedAttributes]{$content}"
    }
    else -> nodeValue?.trim().orEmpty()
}
