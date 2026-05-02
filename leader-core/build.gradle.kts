configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    api(libs.bluetape4k.core)
    api(libs.bluetape4k.idgenerators)
    api(libs.bluetape4k.logging)
    testImplementation(libs.bluetape4k.junit5)

    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
