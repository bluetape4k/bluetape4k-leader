plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.webhook.WebhookPollerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-mongodb"))

    // Mongodb
    implementation(bt4k.bluetape4k.mongodb)
    implementation(bt4k.mongodb.driver.kotlin.coroutine)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(libs.awaitility.kotlin)

    // Testcontainers
    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
}
