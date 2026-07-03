package io.bluetape4k.leader.examples.k8soperator

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorManifestTest {

    @Test
    fun `runtime role does not grant lease delete`() {
        val rbac = readManifest("rbac.yaml")

        rbac shouldContain """resources: ["leases"]"""
        rbac shouldContain """verbs: ["get", "create", "update", "patch"]"""
        rbac.contains("delete").shouldBeFalse()
    }

    @Test
    fun `deployment uses stable image reference and full probe contract`() {
        val deployment = readManifest("deployment.yaml")

        deployment shouldContain "image: ghcr.io/bluetape4k/bluetape4k-k8s-operator:0.5.0"
        deployment.contains(":latest").shouldBeFalse()
        deployment shouldContain "startupProbe:"
        deployment shouldContain "livenessProbe:"
        deployment shouldContain "readinessProbe:"
        deployment shouldContain "path: /actuator/health"
    }

    private fun readManifest(fileName: String): String =
        Files.readString(Path.of("k8s", fileName))
}
