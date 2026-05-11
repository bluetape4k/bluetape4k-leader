configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

kover {
    reports {
        verify {
            rule {
                bound {
                    minValue = 80
                }
            }
        }
    }
}

dependencies {
    api(project(":leader-core"))
    api(libs.curator.recipes)

    testImplementation(testFixtures(project(":leader-core")))

    testImplementation(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
