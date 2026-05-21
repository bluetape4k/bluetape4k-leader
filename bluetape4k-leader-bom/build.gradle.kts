plugins {
    `java-platform`
    `maven-publish`
    signing
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":bluetape4k-leader-core"))
        api(project(":bluetape4k-leader-redis-lettuce"))
        api(project(":bluetape4k-leader-redis-redisson"))
        api(project(":bluetape4k-leader-exposed-core"))
        api(project(":bluetape4k-leader-exposed-jdbc"))
        api(project(":bluetape4k-leader-exposed-r2dbc"))
        api(project(":bluetape4k-leader-mongodb"))
        api(project(":bluetape4k-leader-hazelcast"))
        api(project(":bluetape4k-leader-zookeeper"))
        api(project(":bluetape4k-leader-k8s"))
        api(project(":bluetape4k-leader-spring-boot"))
        api(project(":bluetape4k-leader-ktor"))
        api(project(":bluetape4k-leader-micrometer"))
    }
}

publishing {
    publications {
        create<MavenPublication>("BluetapeLeaderBom") {
            from(components["javaPlatform"])

            pom {
                name.set("bluetape4k-leader-bom")
                description.set("BOM for bluetape4k-leader — Distributed leader election library for Kotlin")
                url.set("https://github.com/bluetape4k/bluetape4k-leader")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("debop")
                        name.set("Sunghyouk Bae")
                        email.set("sunghyouk.bae@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/bluetape4k/bluetape4k-leader.git")
                    developerConnection.set("scm:git:ssh://github.com/bluetape4k/bluetape4k-leader.git")
                    url.set("https://github.com/bluetape4k/bluetape4k-leader")
                }
            }
        }
    }
}

configurePublishingSigning("BluetapeLeaderBom")
