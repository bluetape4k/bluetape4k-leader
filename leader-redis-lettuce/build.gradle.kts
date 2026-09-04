import org.gradle.api.tasks.testing.Test
import java.time.Instant

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(bt4k.bluetape4k.lettuce)
    api(libs.lettuce.core)

    api(bt4k.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.reactive)


    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.virtualthread.jdk25)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.toxiproxy)

    // T7 PR 2 — Abstract*ContractTest 사용
    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("redis-cluster")
    }
}

val clusterTestMatrixFile = layout.projectDirectory.file("src/test/resources/redis-cluster-test-matrix.txt").asFile
val expectedClusterTestNames = clusterTestMatrixFile.readLines()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .also { names ->
        require(names.size == names.toSet().size) {
            "Redis Cluster test matrix contains duplicate names"
        }
    }

val clusterTest = tasks.register<Test>("clusterTest") {
    val expectedClusterTestCount = expectedClusterTestNames.size
    group = "verification"
    description = "Runs Redis Cluster integration tests."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    failOnNoDiscoveredTests = true
    useJUnitPlatform {
        includeTags("redis-cluster")
    }
    mustRunAfter(tasks.named("test"))
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    maxParallelForks = 1
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/clusterTest/binary"))
    reports.junitXml.required.set(true)
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/clusterTest"))
    reports.junitXml.includeSystemOutLog.set(false)
    reports.junitXml.includeSystemErrLog.set(false)
    reports.html.required.set(false)

    val diagnosticsDirectory = layout.buildDirectory.dir("redis-cluster-diagnostics")
    systemProperty("redis.cluster.diagnostics.dir", diagnosticsDirectory.get().asFile.absolutePath)
    doFirst {
        val directory = diagnosticsDirectory.get().asFile
        directory.mkdirs()
        directory.resolve("task-provenance.txt").writeText(
            buildString {
                appendLine("image=tommy351/redis-cluster:6.2")
                appendLine("fixture=io.bluetape4k.testcontainers.storage.RedisClusterServer.Launcher.redisCluster")
                appendLine("task=clusterTest")
                appendLine("expectedTests=$expectedClusterTestCount")
                appendLine("dockerHost=${System.getenv("DOCKER_HOST") ?: "default"}")
                appendLine("startedAt=${Instant.now()}")
            },
        )
    }
    doLast {
        val xmlReports = reports.junitXml.outputLocation.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .toList()
        require(xmlReports.isNotEmpty()) {
            "Redis Cluster test produced no JUnit XML"
        }

        var tests = 0
        var skipped = 0
        var failures = 0
        var errors = 0
        val observedTestNames = mutableSetOf<String>()
        xmlReports.forEach { report ->
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(report)
            val suite = document.documentElement
            tests += suite.getAttribute("tests").toIntOrNull() ?: 0
            skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
            failures += suite.getAttribute("failures").toIntOrNull() ?: 0
            errors += suite.getAttribute("errors").toIntOrNull() ?: 0
            val testCases = suite.getElementsByTagName("testcase")
            for (index in 0 until testCases.length) {
                val testCase = testCases.item(index) as org.w3c.dom.Element
                observedTestNames += testCase.getAttribute("name").removeSuffix("()")
            }
        }
        val missingTestNames = expectedClusterTestNames.filterNot(observedTestNames::contains)
        require(tests >= expectedClusterTestCount && missingTestNames.isEmpty() &&
            skipped == 0 && failures == 0 && errors == 0) {
            "Redis Cluster test scope invalid: expected at least $expectedClusterTestCount tests and " +
                "all matrix names, actual=$tests, missing=$missingTestNames, skipped=$skipped, " +
                "failures=$failures, errors=$errors"
        }

        val runtimeProvenance = diagnosticsDirectory.get().asFile.resolve("cluster-runtime.txt")
        require(runtimeProvenance.isFile) {
            "Redis Cluster runtime provenance is missing: ${runtimeProvenance.absolutePath}"
        }
        val provenanceLines = runtimeProvenance.readLines()
        val imageDigest = provenanceLines.firstOrNull { it.startsWith("image_digest=") }
        val clusterState = provenanceLines.firstOrNull { it.startsWith("cluster_state=") }
        val endpoints = provenanceLines.firstOrNull { it.startsWith("endpoints=") }
        val endpointCount = endpoints?.substringAfter('=')?.split(',')?.count { it.isNotBlank() } ?: 0
        require(imageDigest?.contains("@sha256:") == true && clusterState == "cluster_state=ok" &&
            endpointCount >= 6) {
            "Redis Cluster runtime provenance is incomplete: $provenanceLines"
        }
    }
}
