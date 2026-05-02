configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(libs.bluetape4k.lettuce)
    api(libs.lettuce.core)

    api(libs.bluetape4k.coroutines)
    api(libs.kotlinx.coroutines.reactive)


    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
