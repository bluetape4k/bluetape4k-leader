import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask
import nmcp.NmcpAggregationExtension
import nmcp.NmcpExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.concurrent.TimeUnit

plugins {
    base
    `maven-publish`
    signing
    alias(libs.plugins.kotlin.jvm)

    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.noarg) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.atomicfu)

    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.management)

    alias(libs.plugins.dokka)
    alias(libs.plugins.test.logger)

    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.nmcp) apply false

    alias(libs.plugins.kover)
}

val rootLibs = libs

val centralPublishing = resolveCentralPublishingConfig()
val centralUser: String = centralPublishing.username
val centralPassword: String = centralPublishing.password
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
        mavenCentral()
        google()
        // bluetape4k SNAPSHOT 버전 사용 시
        maven {
            name = "central-snapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        }
    }
    configurations.all {
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    }
}

subprojects {
    if (path == ":examples" || path.startsWith(":examples:")) {
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

    val isExample = path == ":examples" || path.startsWith(":examples:")

    apply {
        plugin<JavaLibraryPlugin>()
        plugin("org.jetbrains.kotlin.jvm")
        plugin("org.jetbrains.kotlinx.atomicfu")
        plugin("org.jetbrains.kotlinx.kover")
        if (!isExample) {
            plugin("maven-publish")
            plugin("signing")
        }
        plugin("io.spring.dependency-management")
        plugin("org.jetbrains.dokka")
        plugin("com.adarshr.test-logger")
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        configurations.matching { it.name == "kotlinCompilerClasspath" || it.name == "kotlinCompilerPluginClasspath" }.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(rootLibs.versions.kotlin.get())
                    because("KGP build-tools requires matching kotlin-compiler version")
                }
            }
        }
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
            mavenBom(rootLibs.bluetape4k.bom.get().toString())
            mavenBom(rootLibs.kotlinx.coroutines.bom.get().toString())
            mavenBom(rootLibs.kotlin.bom.get().toString())
            mavenBom(rootLibs.junit.bom.get().toString())
            mavenBom(rootLibs.micrometer.bom.get().toString())
            mavenBom(rootLibs.testcontainers.bom.get().toString())
        }
    }

    dependencies {
        val api by configurations
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        api(rootLibs.jetbrains.annotations)

        implementation(rootLibs.kotlin.stdlib)
        implementation(rootLibs.kotlin.reflect)
        testImplementation(rootLibs.kotlin.test)
        testImplementation(rootLibs.kotlin.test.junit5)

        implementation(rootLibs.kotlinx.coroutines.core)
        implementation(rootLibs.kotlinx.atomicfu)

        api(rootLibs.slf4j.api)
        testImplementation(rootLibs.logback)
        testImplementation(rootLibs.jcl.over.slf4j)
        testImplementation(rootLibs.jul.to.slf4j)
        testImplementation(rootLibs.log4j.over.slf4j)

        testImplementation(rootLibs.junit.jupiter)
        testRuntimeOnly(rootLibs.junit.platform.engine)

        testImplementation(rootLibs.awaitility.kotlin)
        testImplementation(rootLibs.mockk)
    }

    if (isExample) {
        return@subprojects
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
            mavenCentral()
            maven {
                name = "central-snapshots"
                url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            }
        }
    }

    configurePublishingSigning("BluetapeLeader")
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
        .filter { it.path != ":examples" && !it.path.startsWith(":examples:") }
        .forEach { add("nmcpAggregation", project(it.path)) }
}

dependencies {
    subprojects
        .filter { it.name != "bluetape4k-leader-bom" && it.path != ":examples" && !it.path.startsWith(":examples:") }
        .forEach { sub -> kover(project(sub.path)) }
}
