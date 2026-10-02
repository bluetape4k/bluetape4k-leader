plugins {
    application
}

application {
    mainClass.set("io.bluetape4k.leader.examples.dynamodbexport.DynamoDbExportDemo")
}

dependencies {
    implementation(project(":bluetape4k-leader-dynamodb"))

    // Bluetape4k
    implementation(bt4k.bluetape4k.core)
    testImplementation(bt4k.bluetape4k.junit5)

    // AWS DynamoDB
    implementation(platform(bt4k.aws2.bom))
    implementation(libs.aws2.dynamodb)
    implementation(bt4k.bluetape4k.aws.java)
    implementation(bt4k.aws2.aws.crt)

    // Coroutines
    implementation(bt4k.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Testcontainers
    implementation(bt4k.bluetape4k.testcontainers)
    implementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
}
