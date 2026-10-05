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

    // Exposed
    implementation(bt4k.bluetape4k.exposed.r2dbc)
    testImplementation(bt4k.bluetape4k.exposed.r2dbc.tests)
    implementation(bt4k.exposed.core)
    implementation(bt4k.exposed.r2dbc)

    // R2DBC drivers (compileOnly — 사용자가 선택, demo 는 H2 사용)
    compileOnly(bt4k.r2dbc.postgresql)
    compileOnly(bt4k.r2dbc.mysql)

    runtimeOnly(bt4k.r2dbc.h2)
    runtimeOnly(bt4k.h2.v2)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(bt4k.postgresql)
    testImplementation(bt4k.mysql.connector.j)
}
