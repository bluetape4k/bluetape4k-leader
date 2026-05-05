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
        api(project(":leader-core"))
        api(project(":leader-redis-lettuce"))
        api(project(":leader-redis-redisson"))
        api(project(":leader-exposed-core"))
        api(project(":leader-exposed-jdbc"))
        api(project(":leader-exposed-r2dbc"))
        api(project(":leader-mongodb"))
        api(project(":leader-hazelcast"))
        api(project(":leader-spring-boot"))
        api(project(":leader-micrometer"))
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
    repositories {
        mavenLocal()
    }
}

configurePublishingSigning("BluetapeLeaderBom")
