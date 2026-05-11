plugins {
    `java-test-fixtures`
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

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(libs.bluetape4k.core)
    api(libs.bluetape4k.idgenerators)
    compileOnly(libs.bluetape4k.virtualthread.jdk21)
    testImplementation(libs.bluetape4k.junit5)

    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.reactor)

    // testFixtures: backend module 들이 contract test 를 상속할 수 있도록 노출
    testFixturesApi(libs.bluetape4k.junit5)
    testFixturesApi(libs.kotlin.test)
    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.coroutines.test)
}
