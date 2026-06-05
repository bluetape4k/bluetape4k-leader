plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.dynamodbexport.DynamoDbExportDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-dynamodb"))

    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.testcontainers)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.testcontainers)

    runtimeOnly(libs.logback)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.logback)
}
