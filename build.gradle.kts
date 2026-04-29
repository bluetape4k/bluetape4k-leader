import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    base
    `maven-publish`
    signing
    kotlin("jvm") version Versions.kotlin

    kotlin("plugin.spring") version Versions.kotlin apply false
    kotlin("plugin.allopen") version Versions.kotlin apply false
    kotlin("plugin.noarg") version Versions.kotlin apply false
    kotlin("plugin.serialization") version Versions.kotlin apply false
    id("org.jetbrains.kotlinx.atomicfu") version Versions.kotlinx_atomicfu

    id(Plugins.detekt) version Plugins.Versions.detekt
    id(Plugins.dependency_management) version Plugins.Versions.dependency_management

    id(Plugins.dokka) version Plugins.Versions.dokka
    id(Plugins.testLogger) version Plugins.Versions.testLogger

    id(Plugins.nmcp_aggregation) version Plugins.Versions.nmcp
    id(Plugins.nmcp) version Plugins.Versions.nmcp apply false

    id(Plugins.kover) version Plugins.Versions.kover
}

val centralUser: String = providers.gradleProperty("centralPortalUsername").orElse("").get()
val centralPassword: String = providers.gradleProperty("centralPortalPassword").orElse("").get()
val centralSnapshotsParallelism: Int = providers
    .gradleProperty("centralSnapshotsParallelism")
    .map(String::toInt)
    .orElse(4)
    .get()

val projectGroup: String by project
val baseVersion: String by project
val snapshotVersion: String by project

allprojects {
    group = projectGroup
    version = baseVersion + snapshotVersion

    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}

subprojects {
    if (name != "leader-bom") {
        apply(plugin = Plugins.nmcp)
    }

    configurations.matching { it.name.startsWith("nmcp") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-serialization")) {
                useVersion("1.9.0")
                because("nmcp runtime compatibility")
            }
        }
    }

    plugins.withId(Plugins.nmcp) {
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
    if (name == "leader-bom") {
        return@subprojects
    }

    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        plugin("org.jetbrains.kotlinx.atomicfu")
        plugin(Plugins.kover)
        plugin("maven-publish")
        plugin("signing")
        plugin(Plugins.dependency_management)
        plugin(Plugins.dokka)
        plugin(Plugins.testLogger)
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        kotlin {
            jvmToolchain(21)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_3)
                apiVersion.set(KotlinVersion.KOTLIN_2_3)
                freeCompilerArgs = listOf(
                    "-Xjsr305=strict",
                    "-jvm-default=enable",
                    "-Xstring-concat=indy",
                    "-Xcontext-parameters",
                    "-Xannotation-default-target=param-property"
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

        val reportMerge by registering(ReportMergeTask::class) {
            val file = rootProject.layout.buildDirectory.asFile.get().resolve("reports/detekt/merged.xml")
            output.set(file)
        }
        withType<Detekt>().configureEach detekt@{
            finalizedBy(reportMerge)
            reportMerge.configure { input.from(this@detekt.xmlReportFile) }
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
            mavenBom(Libs.bluetape4k_bom)
            mavenBom(Libs.kotlinx_coroutines_bom)
            mavenBom(Libs.kotlin_bom)
            mavenBom(Libs.junit_bom)
            mavenBom(Libs.micrometer_bom)
            mavenBom(Libs.testcontainers_bom)
        }
    }

    dependencies {
        val api by configurations
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        api(Libs.jetbrains_annotations)

        implementation(Libs.kotlin_stdlib)
        implementation(Libs.kotlin_reflect)
        testImplementation(Libs.kotlin_test)
        testImplementation(Libs.kotlin_test_junit5)

        implementation(Libs.kotlinx_coroutines_core)
        implementation(Libs.kotlinx_atomicfu)

        api(Libs.slf4j_api)
        testImplementation(Libs.logback)
        testImplementation(Libs.jcl_over_slf4j)
        testImplementation(Libs.jul_to_slf4j)
        testImplementation(Libs.log4j_over_slf4j)

        testImplementation(Libs.junit_jupiter)
        testRuntimeOnly(Libs.junit_platform_engine)

        testImplementation(Libs.kluent)
        testImplementation(Libs.awaitility_kotlin)
        testImplementation(Libs.mockk)
    }

    publishing {
        publications {
            create<MavenPublication>("BluetapeLeader") {
                val sourcesJar by tasks.registering(Jar::class) {
                    archiveClassifier.set("sources")
                    from(sourceSets["main"].allSource)
                }
                val javadocJar by tasks.registering(Jar::class) {
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
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
            mavenLocal()
        }
    }

    signing {
        useGpgCmd()
        sign(publishing.publications["BluetapeLeader"])
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
        .filter { it.name != "leader-bom" }
        .forEach { add("nmcpAggregation", project(it.path)) }
}

dependencies {
    subprojects
        .filter { it.name != "leader-bom" }
        .forEach { sub -> kover(project(sub.path)) }
}
