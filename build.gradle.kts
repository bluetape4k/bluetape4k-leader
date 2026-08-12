import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import dev.detekt.gradle.report.ReportMergeTask
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.concurrent.TimeUnit

plugins {
    base
    `maven-publish`
    signing
    alias(bt4k.plugins.kotlin.jvm)

    alias(bt4k.plugins.kotlin.spring) apply false
    alias(bt4k.plugins.kotlin.allopen) apply false
    alias(bt4k.plugins.kotlin.noarg) apply false
    alias(bt4k.plugins.kotlin.serialization) apply false
    alias(bt4k.plugins.kotlinx.atomicfu)
    alias(bt4k.plugins.kotlinx.benchmark) apply false

    alias(bt4k.plugins.detekt.dev) apply false
    alias(bt4k.plugins.dependency.management)

    alias(bt4k.plugins.dokka)
    alias(bt4k.plugins.test.logger)

    alias(bt4k.plugins.nmcp.aggregation)
    alias(bt4k.plugins.nmcp) apply false

    alias(bt4k.plugins.kover) apply false
}

val rootLibs = libs
val rootBt4k = bt4k
val bt4kCatalog = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>().named("bt4k")
fun bt4kLibrary(alias: String) = bt4kCatalog.findLibrary(alias).get()
fun bt4kVersion(alias: String): String {
    val version = bt4kCatalog.findVersion(alias).get()
    return version.requiredVersion
        .ifBlank { version.preferredVersion }
        .ifBlank { version.strictVersion }
}

val requestedTaskNames = gradle.startParameter.taskNames + gradle.startParameter.excludedTaskNames
fun isRequestedTask(token: String): Boolean =
    requestedTaskNames.any { it.substringAfterLast(':').contains(token, ignoreCase = true) }

val detektRequested = requestedTaskNames.isEmpty() || isRequestedTask("detekt")
val koverRequested = requestedTaskNames.isEmpty() || isRequestedTask("kover")
val reusableTestcontainersExamplePaths = setOf(
    ":examples:batch-scheduler",
    ":examples:cache-warmer",
    ":examples:consul-maintenance",
    ":examples:dynamodb-export",
    ":examples:etcd-reconciler",
    ":examples:prometheus-dashboard",
    ":examples:rate-limiter",
    ":examples:redisson-watchdog",
    ":examples:zookeeper-scheduler",
)

if (detektRequested) {
    apply(plugin = "dev.detekt")
}
if (koverRequested) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

buildscript.configurations.getByName("classpath").resolutionStrategy.eachDependency {
    if (requested.group == "com.mysql" && requested.name == "mysql-connector-j") {
        useVersion(bt4kVersion("mysql-connector-j"))
        because("Keep Gradle plugin classpath MySQL driver on the centrally governed catalog line")
    }
    if (requested.group == "com.google.protobuf" && requested.name == "protobuf-java") {
        useVersion(bt4kVersion("protobuf"))
        because("Keep Gradle plugin classpath protobuf on the centrally governed security line")
    }
}

val centralPublishing = resolveCentralPublishingConfig()
val centralUser: String = centralPublishing.username
val centralPassword: String = centralPublishing.password
val centralSnapshotsParallelism: Int = providers
    .gradleProperty("centralSnapshotsParallelism")
    .map(String::toInt)
    .orElse(4)
    .get()

val projectGroup = providers.gradleProperty("projectGroup").get()
val baseVersion = providers.gradleProperty("baseVersion").get()
val snapshotVersion = providers.gradleProperty("snapshotVersion").get()
val bluetape4kVirtualThreadJdk25Version = providers
    .gradleProperty("bluetape4kVirtualThreadJdk25Version")
    .orElse("1.13.0-SNAPSHOT")
    .get()

fun Project.isNonPublishedProject(): Boolean =
    path == ":examples" || path.startsWith(":examples:") || path == ":benchmark"

allprojects {
    group = projectGroup
    version = baseVersion + snapshotVersion

    repositories {
        mavenCentral()
        google()
        // bluetape4k SNAPSHOT 버전 사용 시
        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(1, TimeUnit.DAYS)
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
    }
    if (isNonPublishedProject()) {
        return@subprojects
    }
    apply(plugin = "com.gradleup.nmcp")

    configurations.matching { it.name.startsWith("nmcp") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization")) {
                useVersion("1.9.0")
                because("nmcp runtime compatibility")
            }
        }
    }

    plugins.withId("com.gradleup.nmcp") {
        extensions.configure<NmcpExtension>("nmcp") {
            publishAllPublicationsToCentralPortal {
                username.set(centralUser)
                password.set(centralPassword)
                publishingType.set("AUTOMATIC")
                uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
            }
        }
    }
}

subprojects {
    if (name == "bluetape4k-leader-bom") {
        return@subprojects
    }

    val isNonPublished = isNonPublishedProject()

    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        if (detektRequested) {
            plugin("dev.detekt")
        }
        plugin("org.jetbrains.kotlinx.atomicfu")
        if (koverRequested) {
            plugin("org.jetbrains.kotlinx.kover")
        }
        if (!isNonPublished) {
            plugin("maven-publish")
            plugin("signing")
        }
        plugin("io.spring.dependency-management")
        plugin("org.jetbrains.dokka")
        plugin("com.adarshr.test-logger")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        if (path in reusableTestcontainersExamplePaths) {
            kotlin.sourceSets.named("main") {
                kotlin.srcDir(rootProject.file("examples/shared/src/main/kotlin"))
            }
        }
        configurations.matching { it.name == "kotlinCompilerClasspath" || it.name == "kotlinCompilerPluginClasspath" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(bt4kVersion("kotlin"))
                    because("KGP build-tools requires matching kotlin-compiler version")
                }
            }
        }
        kotlin {
            jvmToolchain(25)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_4)
                apiVersion.set(KotlinVersion.KOTLIN_2_4)
                jvmTarget.set(JvmTarget.JVM_25)
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-jvm-default=enable",
                    "-Xstring-concat=indy",
                )
                val experimentalAnnotations = listOf(
                    "kotlin.RequiresOptIn",
                    "kotlin.ExperimentalStdlibApi",
                    "kotlin.contracts.ExperimentalContracts",
                    "kotlin.experimental.ExperimentalTypeInference",
                    "kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "kotlinx.coroutines.InternalCoroutinesApi",
                    "kotlinx.coroutines.FlowPreview",
                    "kotlinx.coroutines.DelicateCoroutinesApi",
                )
                freeCompilerArgs.addAll(experimentalAnnotations.map { "-opt-in=$it" })
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlinx.atomicfu") {
        atomicfu {
            transformJvm = true
            jvmVariant = "VH"
        }
    }

    tasks {
        abstract class TestMutexService: BuildService<BuildServiceParameters.None>
        abstract class SigningMutexService: BuildService<BuildServiceParameters.None>

        val testMutex = gradle.sharedServices.registerIfAbsent("test-mutex", TestMutexService::class) {
            maxParallelUsages.set(1)
        }
        val signingMutex = gradle.sharedServices.registerIfAbsent("signing-mutex", SigningMutexService::class) {
            maxParallelUsages.set(1)
        }

        compileJava { options.isIncremental = true }
        compileKotlin { compilerOptions { incremental = true } }

        test {
            usesService(testMutex)
            useJUnitPlatform()
            jvmArgs(
                "-Xshare:off",
                "-Xms2M",
                "-Xmx4G",
                "-XX:+UseG1GC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+EnableDynamicAgentLoading",
                "--enable-preview",
                "-Didea.io.use.nio2=true"
            )
            testLogging {
                showExceptions = true
                showCauses = true
                showStackTraces = true
                events("failed")
            }
        }

        withType<Sign>().configureEach {
            usesService(signingMutex)
        }

        testlogger {
            theme = com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
        }

        if (detektRequested) {
            val productionDetektSourceRoots = buildList {
                add(project.file("src/main/kotlin"))
                add(project.file("src/main/java"))
                if (path in reusableTestcontainersExamplePaths) {
                    add(rootProject.file("examples/shared/src/main/kotlin"))
                }
            }
            val productionDetektSources = project.files(productionDetektSourceRoots)
            extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
                baseline.set(project.layout.projectDirectory.file("detekt-baseline.xml"))
                ignoreFailures = false
            }
            named<Detekt>("detekt") {
                setSource(productionDetektSources)
                include("**/*.kt", "**/*.kts", "**/*.java")
                baseline.set(project.layout.projectDirectory.file("detekt-baseline.xml"))
                reports.checkstyle.required.set(true)
            }
            named<DetektCreateBaselineTask>("detektBaseline") {
                setSource(productionDetektSources)
                include("**/*.kt", "**/*.kts", "**/*.java")
                baseline.set(project.layout.projectDirectory.file("detekt-baseline.xml"))
            }
        }

        jar {
            manifest.attributes["Specification-Title"] = project.name
            manifest.attributes["Specification-Version"] = project.version
            manifest.attributes["Implementation-Title"] = project.name
            manifest.attributes["Implementation-Version"] = project.version
            manifest.attributes["Automatic-Module-Name"] = project.name.replace('-', '.')
            manifest.attributes["Created-By"] =
                "${System.getProperty("java.version")} (${System.getProperty("java.specification.vendor")})"
        }

        dokka {
            dokkaPublications.html {
                outputDirectory.set(layout.buildDirectory.asFile.get().resolve("javadoc"))
            }
            dokkaSourceSets.configureEach {
                includes.from(project.files("README.md"))
            }
        }

        clean {
            doLast {
                delete("./.project")
                delete("./out")
                delete("./bin")
            }
        }
    }

    dependencyManagement {
        setApplyMavenExclusions(false)
        imports {
            mavenBom(bt4kLibrary("bluetape4k-bom").get().toString())
            mavenBom("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4kVersion("kotlinx-coroutines")}")
            mavenBom("org.jetbrains.kotlin:kotlin-bom:${bt4kVersion("kotlin")}")
            mavenBom(rootBt4k.junit.bom.get().toString())
            mavenBom(rootBt4k.micrometer.bom.get().toString())
            mavenBom("org.testcontainers:testcontainers-bom:${bt4kVersion("testcontainers")}")
            mavenBom(bt4kLibrary("aws2-bom").get().toString())
            mavenBom("io.netty:netty-bom:${bt4kVersion("netty")}")
            mavenBom("com.google.protobuf:protobuf-bom:${bt4kVersion("protobuf")}")
            mavenBom("io.vertx:vertx-dependencies:${bt4kVersion("vertx")}")
        }

        dependencies {

            // <central-catalog-local-aliases>

            dependency("io.lettuce:lettuce-core:${bt4kVersion("lettuce")}")

            dependency("org.awaitility:awaitility-kotlin:${bt4kVersion("awaitility")}")

            dependency("org.jetbrains.exposed:exposed-dao:${bt4kVersion("exposed")}")

            dependency("org.jetbrains.exposed:exposed-kotlin-datetime:${bt4kVersion("exposed")}")

            dependency("org.jetbrains.kotlin:kotlin-bom:${bt4kVersion("kotlin")}")

            dependency("org.jetbrains.kotlinx:kotlinx-coroutines-bom:${bt4kVersion("kotlinx-coroutines")}")

            dependency("org.slf4j:jcl-over-slf4j:${bt4kVersion("slf4j")}")

            dependency("org.slf4j:jul-to-slf4j:${bt4kVersion("slf4j")}")

            dependency("org.slf4j:log4j-over-slf4j:${bt4kVersion("slf4j")}")

            dependency("org.testcontainers:testcontainers-bom:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-junit-jupiter:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-mongodb:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-mysql:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-postgresql:${bt4kVersion("testcontainers")}")

            dependency("org.testcontainers:testcontainers-toxiproxy:${bt4kVersion("testcontainers")}")

            // </central-catalog-local-aliases>
            dependency("com.hazelcast:hazelcast:${bt4kVersion("hazelcast")}")
            dependency("com.google.protobuf:protobuf-java:${bt4kVersion("protobuf")}")
            dependency("io.netty:netty-codec-http:${bt4kVersion("netty")}")
            dependency("io.netty:netty-codec-http2:${bt4kVersion("netty")}")
            dependency("io.vertx:vertx-core:${bt4kVersion("vertx")}")
            dependency("com.mysql:mysql-connector-j:${bt4kVersion("mysql-connector-j")}")
            dependency("org.postgresql:postgresql:${bt4kVersion("postgresql")}")
            dependency("io.r2dbc:r2dbc-h2:${bt4kVersion("r2dbc-h2")}")
            dependency("org.redisson:redisson:${bt4kVersion("redisson")}")
            dependency("org.slf4j:slf4j-api:${bt4kVersion("slf4j")}")
        }
    }

    if (path in setOf(":bluetape4k-leader-k8s", ":examples:k8s-lease", ":examples:k8s-operator")) {
        dependencyManagement {
            imports {
                mavenBom("io.netty:netty-bom:${bt4kVersion("netty4")}")
                mavenBom("io.vertx:vertx-dependencies:${bt4kVersion("vertx4")}")
            }
            dependencies {
                dependency("io.netty:netty-codec-http:${bt4kVersion("netty4")}")
                dependency("io.netty:netty-codec-http2:${bt4kVersion("netty4")}")
                dependency("io.vertx:vertx-core:${bt4kVersion("vertx4")}")
            }
        }
    }

    dependencies {
        add("api", rootBt4k.jetbrains.annotations)

        add("implementation", rootLibs.kotlin.stdlib)
        add("implementation", rootLibs.kotlin.reflect)
        add("testImplementation", rootLibs.kotlin.test)
        add("testImplementation", rootLibs.kotlin.test.junit5)

        add("implementation", rootLibs.kotlinx.coroutines.core)
        add("implementation", rootBt4k.kotlinx.atomicfu)

        add("api", bt4kLibrary("slf4j-api"))
        add("testImplementation", rootBt4k.logback.asProvider())
        add("testImplementation", rootLibs.jcl.over.slf4j)
        add("testImplementation", rootLibs.jul.to.slf4j)
        add("testImplementation", rootLibs.log4j.over.slf4j)

        add("testImplementation", rootLibs.junit.jupiter)
        add("testRuntimeOnly", rootLibs.junit.platform.engine)

        add("testImplementation", rootLibs.awaitility.kotlin)
        add("testImplementation", rootBt4k.mockk)
    }

    if (isNonPublished) {
        return@subprojects
    }

    publishing {
        publications {
            create<MavenPublication>("BluetapeLeader") {
                val sourcesJar = tasks.register<Jar>("sourcesJar") {
                    archiveClassifier.set("sources")
                    from(sourceSets["main"].allSource)
                }
                val javadocJar = tasks.register<Jar>("javadocJar") {
                    archiveClassifier.set("javadoc")
                    from(layout.buildDirectory.asFile.get().resolve("javadoc"))
                }
                from(components["java"])
                artifact(sourcesJar)
                artifact(javadocJar)

                pom {
                    name.set(project.name)
                    description.set("Distributed leader election library for Kotlin — coroutine-native, virtual-thread aware")
                    url.set("https://github.com/bluetape4k/bluetape4k-leader")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("debop")
                            name.set("Sunghyouk Bae")
                            email.set("sunghyouk.bae@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/bluetape4k/bluetape4k-leader.git")
                        developerConnection.set("scm:git:ssh://github.com/bluetape4k/bluetape4k-leader.git")
                        url.set("https://github.com/bluetape4k/bluetape4k-leader")
                    }
                }
            }
        }
        repositories {
            mavenCentral()
            maven {
                name = "central-snapshots"
                url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            }
        }
    }

    configurePublishingSigning("BluetapeLeader")
}

if (detektRequested) {
    val moduleDetektTasks = subprojects.mapNotNull { subproject ->
        subproject.tasks.findByName("detekt") as? Detekt
    }
    val reportMerge = tasks.register<ReportMergeTask>("reportMerge") {
        val file = layout.buildDirectory.asFile.get().resolve("reports/detekt/merged.xml")
        output.set(file)
        input.from(moduleDetektTasks.map { it.reports.checkstyle.outputLocation })
        dependsOn(moduleDetektTasks)
    }

    val detektProductionSourceGuard = tasks.register("detektProductionSourceGuard") {
        group = "verification"
        description = "Fails when no Kotlin production sources are available for Detekt."
        doLast {
            val sourceModules = subprojects
                .mapNotNull { subproject ->
                    val sourceRoots = buildList {
                        add(subproject.file("src/main/kotlin"))
                        if (subproject.path in reusableTestcontainersExamplePaths) {
                            add(rootProject.file("examples/shared/src/main/kotlin"))
                        }
                    }
                    val files = sourceRoots
                        .flatMap { sourceRoot ->
                            subproject.fileTree(sourceRoot) {
                                include("**/*.kt", "**/*.kts")
                            }.files
                        }.toSet()
                    files.size.takeIf { it > 0 }?.let { count -> subproject.path to count }
                }
            val productionSources = sourceModules.sumOf { (_, count) -> count }
            check(productionSources > 0) {
                "Detekt expected at least one Kotlin production source file, but found none"
            }
            logger.lifecycle(
                "Detekt production modules: ${sourceModules.joinToString { (path, count) -> "$path=$count" }}",
            )
        }
    }

    gradle.projectsEvaluated {
        tasks.named("detekt") {
            dependsOn(detektProductionSourceGuard)
            dependsOn(moduleDetektTasks)
            finalizedBy(reportMerge)
        }
    }
}

subprojects {
    configurations.configureEach {
        exclude(
            group = "io.github.bluetape4k",
            module = "bluetape4k-virtualthread-jdk21",
        )
        resolutionStrategy.force(
            "io.github.bluetape4k:bluetape4k-virtualthread-api:$bluetape4kVirtualThreadJdk25Version",
            "io.github.bluetape4k:bluetape4k-virtualthread-jdk25:$bluetape4kVirtualThreadJdk25Version",
        )
        resolutionStrategy.eachDependency {
            if (requested.group == "io.github.bluetape4k" &&
                requested.name in setOf(
                    "bluetape4k-virtualthread-api",
                    "bluetape4k-virtualthread-jdk25",
                )) {
                useVersion(bluetape4kVirtualThreadJdk25Version)
                because("JDK 25 runtime uses one matching virtual-thread API/provider snapshot")
            }
        }
    }
}

val binaryCompatibilityProjects = subprojects.filterNot { it.isNonPublishedProject() || it.name == "bluetape4k-leader-bom" }
tasks.register<Exec>("checkBinaryCompatibility") {
    group = "verification"
    description = "Compare publishable JVM artifacts with the configured published baseline."
    dependsOn(binaryCompatibilityProjects.map { "${it.path}:jar" })
    commandLine("python3", rootProject.file("scripts/compatibility/check_binary_api.py").absolutePath)
}

val publishedProjects = subprojects.filterNot(Project::isNonPublishedProject)
val verifyPublishedPomLicenses = tasks.register("verifyPublishedPomLicenses") {
    group = "verification"
    description = "Verifies MIT license metadata in every publishable Maven POM."
    doLast {
        val pomFiles = publishedProjects.flatMap { project ->
            project.fileTree(project.layout.buildDirectory.dir("publications")) {
                include("*/pom-default.xml")
            }.files
        }.sortedBy(File::getPath)
        check(pomFiles.size == 17) {
            "Expected 17 publishable POMs, found ${pomFiles.size}: ${pomFiles.joinToString()}"
        }
        check(rootProject.file("LICENSE").readText().startsWith("MIT License")) {
            "LICENSE must start with MIT License"
        }
        val readmeLicenseReferences = mapOf(
            rootProject.file("README.md") to listOf(
                "[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)",
                "MIT License",
                "[LICENSE](LICENSE)",
            ),
            rootProject.file("README.ko.md") to listOf(
                "[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)",
                "MIT License",
                "[LICENSE](LICENSE)",
            ),
        )
        readmeLicenseReferences.forEach { (readme, expectedReferences) ->
            val text = readme.readText()
            expectedReferences.forEach { expectedReference ->
                check(expectedReference in text) {
                    "README license reference '$expectedReference' is missing in $readme"
                }
            }
        }
        val licenseBlock = Regex("(?s)<licenses>.*?</licenses>")
        pomFiles.forEach { pom ->
            val text = pom.readText()
            val blocks = licenseBlock.findAll(text).map(MatchResult::value).toList()
            check(blocks.size == 1) { "Expected one license block in $pom, found ${blocks.size}" }
            val block = blocks.single()
            check("<name>MIT License</name>" in block) { "MIT license name missing in $pom" }
            check("<url>https://opensource.org/licenses/MIT</url>" in block) {
                "MIT license URL missing in $pom"
            }
            check("<distribution>repo</distribution>" in block) {
                "MIT license distribution missing in $pom"
            }
            check("Apache-2.0" !in text && "Apache License" !in text) {
                "Apache license metadata remains in $pom"
            }
        }
        logger.lifecycle("Verified MIT license metadata in ${pomFiles.size} publishable POMs")
    }
}
val publicationAggregationTaskNames = setOf(
    "publishAggregationToCentralPortal",
    "publishAggregationToCentralPortalSnapshots",
    "publishAggregationToCentralSnapshots",
)
tasks.matching { task -> task.name in publicationAggregationTaskNames }.configureEach {
    dependsOn(verifyPublishedPomLicenses)
}
subprojects.filterNot(Project::isNonPublishedProject).forEach { project ->
    project.tasks.withType<PublishToMavenRepository>().configureEach {
        dependsOn(verifyPublishedPomLicenses)
    }
}
gradle.projectsEvaluated {
    val publishedPomTasks = publishedProjects.flatMap { project ->
        project.tasks.matching { task -> task.name.startsWith("generatePomFileFor") }.toList()
    }
    check(publishedPomTasks.size == 17) {
        "Expected 17 publication POM tasks, found ${publishedPomTasks.size}"
    }
    if (isRequestedTask("verifyPublishedPomLicenses")) {
        publishedPomTasks.forEach { task -> task.outputs.upToDateWhen { false } }
    }
    verifyPublishedPomLicenses.configure { dependsOn(publishedPomTasks) }
}

val manualModuleInventory = layout.buildDirectory.file("manual/module-inventory.json")

tasks.register("exportManualModuleInventory") {
    group = "documentation"
    description = "Exports the registered Gradle project inventory for manual validation."

    val repositoryRoot = project.rootDir.toPath()
    val modules = project.subprojects.sortedBy(Project::getPath).map { module ->
        val sourceDir = repositoryRoot.relativize(module.projectDir.toPath())
            .toString().replace(File.separatorChar, '/')
        val kind = when {
            sourceDir.startsWith("examples/") -> "example"
            sourceDir == "benchmark" || sourceDir.startsWith("benchmark/") -> "benchmark"
            else -> "library"
        }
        linkedMapOf(
            "gradlePath" to module.path,
            "projectName" to module.name,
            "sourceDir" to sourceDir,
            "kind" to kind,
        )
    }
    val inventoryJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(modules)) + "\n"
    inputs.property("inventoryJson", inventoryJson)
    outputs.file(manualModuleInventory)
    doLast {
        outputs.files.singleFile.apply {
            parentFile.mkdirs()
            writeText(inventoryJson)
        }
    }
}

extensions.configure<NmcpAggregationExtension>("nmcpAggregation") {
    centralPortal {
        username.set(centralUser)
        password.set(centralPassword)
        publishingType.set("AUTOMATIC")
        uploadSnapshotsParallelism.set(centralSnapshotsParallelism)
    }
}

dependencies {
    subprojects
        .filter { !it.isNonPublishedProject() }
        .forEach { add("nmcpAggregation", project(mapOf("path" to it.path))) }
}

if (koverRequested) {
    dependencies {
        subprojects
            .filter { it.name != "bluetape4k-leader-bom" && !it.isNonPublishedProject() }
            .forEach { sub -> add("kover", project(mapOf("path" to sub.path))) }
    }
}
