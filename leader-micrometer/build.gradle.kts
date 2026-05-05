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

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(project(":leader-core"))
    api(libs.micrometer.core)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.micrometer.registry.prometheus)
}
