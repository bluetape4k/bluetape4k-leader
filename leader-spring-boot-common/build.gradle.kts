plugins {
    alias(libs.plugins.dependency.management)
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

// Boot 3 BOM 을 compileOnly 로만 import — 버전 lock 용 (transitive 누출 회피).
// 실제 Spring 의존성은 consumer (leader-spring-boot3-aop / leader-spring-boot4-aspectj) 가 자체 BOM 으로 제공.
dependencyManagement {
    imports {
        mavenBom(libs.spring.boot3.dependencies.get().toString())
        mavenBom(libs.kotlin.bom.get().toString())
    }
}

dependencies {
    api(project(":leader-core"))

    // [T2.23][Q-P3 (b)] spring-context/aop/expression 모두 compileOnly — Boot 3 6.2.x 로 lock,
    // Boot 4 module 은 7.x runtime 으로 override
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.aop)
    compileOnly(libs.spring.expression)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.configuration.processor)
    compileOnly(libs.aspectjweaver)

    // [T2.23] SpEL Expression 캐시 + kotlin-reflect (#argName 평가)
    implementation(libs.caffeine)
    implementation(libs.kotlin.reflect)
    implementation(libs.bluetape4k.logging)

    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.kluent)
    testImplementation(libs.mockk)
    testImplementation(libs.spring.context)
    testImplementation(libs.spring.aop)
    testImplementation(libs.spring.expression)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.actuator)
    testImplementation(libs.aspectjweaver)
}
