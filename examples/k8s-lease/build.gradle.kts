plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.k8slease.K8sLeaseLeaderElectionExample")
}

dependencies {
    implementation(libs.bluetape4k.core)
    implementation(libs.fabric8.kubernetes.client)
    implementation(libs.bluetape4k.logging)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.fabric8.kubernetes.client)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    runtimeOnly(libs.logback)
    testRuntimeOnly(libs.logback)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("k8s")
    }
}

val k8sTest by tasks.registering(Test::class) {
    description = "Runs K3s-backed Kubernetes Lease integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP

    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)

    useJUnitPlatform {
        includeTags("k8s")
    }

    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}
