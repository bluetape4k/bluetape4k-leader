package io.bluetape4k.leader.examples.k8soperator

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorManifestTest {

    companion object: KLogging()

    @Test
    fun `runtime role does not grant lease delete`() {
        val rbac = readManifest("rbac.yaml")

        log.debug { "rbac=$rbac" }

        rbac shouldContain """resources: [ "leases" ]"""
        rbac shouldContain """verbs: [ "get", "create", "update", "patch" ]"""
        rbac shouldNotContain "delete"
    }

    @Test
    fun `deployment uses stable image reference and full probe contract`() {
        val deployment = readManifest("deployment.yaml")

        log.debug { "deployment: $deployment" }

        deployment shouldContain "image: ghcr.io/bluetape4k/bluetape4k-k8s-operator:0.5.0"
        deployment shouldContain "startupProbe:"
        deployment shouldContain "livenessProbe:"
        deployment shouldContain "readinessProbe:"
        deployment shouldContain "path: /actuator/health"
        deployment shouldNotContain ":latest"
    }

    private fun readManifest(fileName: String): String =
        Files.readString(Path.of("k8s", fileName))
}
