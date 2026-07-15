plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.tenant.TenantAggregatorDemo")
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    implementation(project(":bluetape4k-leader-exposed-r2dbc"))

    implementation(bt4k.exposed.core)
    implementation(bt4k.exposed.r2dbc)

    // R2DBC drivers (compileOnly — 사용자가 선택, demo 는 H2 사용)
    compileOnly(libs.r2dbc.postgresql)

    runtimeOnly(bt4k.r2dbc.h2)
    runtimeOnly(libs.h2.v2)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(bt4k.bluetape4k.exposed.r2dbc.tests)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.r2dbc.postgresql)
    testImplementation(bt4k.r2dbc.h2)

    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(bt4k.postgresql)
}
