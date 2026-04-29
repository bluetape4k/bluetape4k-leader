plugins {
    kotlin("plugin.spring")
    kotlin("plugin.allopen")
    id(Plugins.spring_boot) version Plugins.Versions.spring_boot3 apply false
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(Libs.spring_boot3_dependencies)
    }
}

dependencies {
    api(project(":leader-core"))
    compileOnly(project(":leader-redis-lettuce"))
    compileOnly(project(":leader-redis-redisson"))
    compileOnly(project(":leader-exposed-jdbc"))
    compileOnly(project(":leader-exposed-r2dbc"))
    compileOnly(project(":leader-mongodb"))
    compileOnly(project(":leader-hazelcast"))
    compileOnly(project(":leader-micrometer"))

    api(Libs.spring_boot_autoconfigure)
    compileOnly(Libs.spring_boot_configuration_processor)
    compileOnly(Libs.spring_context)
    compileOnly(Libs.spring_tx)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.kotlinx_coroutines_test)
    testImplementation(Libs.spring_boot_test)
    testImplementation(Libs.springmockk)
    testImplementation(project(":leader-redis-redisson"))
    testImplementation(Libs.testcontainers)
    testImplementation(Libs.testcontainers_junit_jupiter)
}

// Spring Boot Configuration Processor
tasks.compileJava {
    inputs.files(tasks.processResources)
}
