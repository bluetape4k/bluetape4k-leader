configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":bluetape4k-leader-core"))
    api(libs.aws2.dynamodb)

    // Bluetape4k AWS
    implementation(platform(bt4k.aws2.bom))
    api(bt4k.bluetape4k.aws.java)
    api(bt4k.aws2.aws.crt)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(testFixtures(project(":bluetape4k-leader-core")))
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.mockk)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
