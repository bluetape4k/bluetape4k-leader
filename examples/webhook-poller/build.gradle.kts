plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.webhook.WebhookPollerDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-mongodb"))

    implementation(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(bt4k.bluetape4k.junit5)
    testImplementation(bt4k.bluetape4k.testcontainers)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mongodb)
}
