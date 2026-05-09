# Design Spec — Issue #104: Drop Spring Boot 3, Merge Spring Boot Modules

**Date**: 2026-05-05  
**Issue**: [#104](https://github.com/bluetape4k/bluetape4k-leader/issues/104)  
**Author**: debop  
**Status**: Approved

---

## 1. 배경 및 목표

현재 `leader-spring-boot-common`, `leader-spring-boot3`, `leader-spring-boot4` 세 모듈을 유지하는 구조는 운영 부담이 크다. Spring Boot 4+만 지원하기로 결정함에 따라:

- `leader-spring-boot3` 모듈 제거
- `leader-spring-boot-common` + `leader-spring-boot4` → `leader-spring-boot` 통합
- Spring 불필요 타입을 `leader-core`로 이동하여 모듈 의존성 단순화

---

## 2. 범위

### 2.1 모듈 변경

| 변경 | 상세 |
|------|------|
| **삭제** | `leader-spring-boot-common` |
| **삭제** | `leader-spring-boot3` |
| **삭제** | `leader-spring-boot4` |
| **신규** | `leader-spring-boot` (boot4 + common 통합) |
| **확장** | `leader-core` — annotation 3개 + metrics SPI 2개 추가 |
| **수정** | `leader-micrometer` — common 의존 → core 의존으로 교체 |
| **수정** | `leader-bom`, `settings.gradle.kts` |
| **수정** | `.github/workflows/ci.yml`, `nightly.yml` |

### 2.2 파일 분류 및 처리

#### `leader-core`로 이동 (순수 Kotlin, Spring 의존 없음)

| 원본 파일 (leader-spring-boot-common) | 새 위치 (leader-core) | 새 패키지 |
|---|---|---|
| `aop/LeaderElection.kt` | `annotation/LeaderElection.kt` | `io.bluetape4k.leader.annotation` |
| `aop/LeaderGroupElection.kt` | `annotation/LeaderGroupElection.kt` | `io.bluetape4k.leader.annotation` |
| `aop/LeaderAspectFailureMode.kt` | `annotation/LeaderAspectFailureMode.kt` | `io.bluetape4k.leader.annotation` |
| `aop/metrics/LeaderAopMetricsRecorder.kt` | `metrics/LeaderAopMetricsRecorder.kt` | `io.bluetape4k.leader.metrics` |
| `aop/metrics/SkipReason.kt` | `metrics/SkipReason.kt` | `io.bluetape4k.leader.metrics` |

#### 삭제 (dead code / Boot 4 비호환)

| 파일 | 이유 |
|---|---|
| `aop/health/LeaderAopHealthIndicator.kt` | Boot 4에서 HealthIndicator 경로 변경 (`spring.boot.health.contributor`), Boot 3 전용 |
| `config/LeaderElectionConfigSupport.kt` | 상속자 없음 (zero inheritors), Boot3/4 분리 시절 설계 |
| `aop/metrics/LeaderAopMetricsRecorder.kt`, `SkipReason.kt` | `leader-core`로 이동 후 삭제 |
| `aop/LeaderElection.kt`, `LeaderGroupElection.kt`, `LeaderAspectFailureMode.kt` | `leader-core`로 이동 후 삭제 |
| **leader-spring-boot3** `metrics/LeaderMicrometerHealthAutoConfiguration.kt` | Boot 3 전용 `HealthIndicator` |

#### `leader-spring-boot`로 이동 (Spring 의존 — common에서)

모든 나머지 `leader-spring-boot-common` 파일:
- `aop/LeaderAspectOrder.kt`, `LeaderBeanSelector.kt`, `LeaderElectionAspect.kt`, `LeaderGroupElectionAspect.kt`
- `aop/cache/FactoryCacheKey.kt` (**주의**: 이 파일에 `FactoryCacheKey` AND `GroupFactoryCacheKey` 두 클래스 포함 — 분리 금지)
- `aop/properties/LeaderAopProperties.kt`
- `aop/spel/SpelExpressionEvaluator.kt`
- `aop/util/AnnotationLookup.kt`, `DurationParser.kt`, `LockNameValidator.kt`
- `aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt`
- `properties/LeaderElectionProperties.kt`, `LeaderGroupProperties.kt`

#### ⚠️ 필수 import 업데이트 (컴파일러가 잡지 못하는 런타임 버그 방지)

이동/이름변경 후 반드시 수정해야 하는 파일별 import:

| 파일 | 수정 내용 |
|------|-----------|
| `LeaderElectionAspect.kt` | `@Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderElection)")` → `@Around("@annotation(io.bluetape4k.leader.annotation.LeaderElection)")` (**⚠️ CRITICAL: 문자열 리터럴 — 컴파일러 미검출, 침묵하며 aspect 동작 중단**) |
| `LeaderGroupElectionAspect.kt` | `@Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderGroupElection)")` → `@Around("@annotation(io.bluetape4k.leader.annotation.LeaderGroupElection)")` |
| `LeaderAnnotationValidatorBeanPostProcessor.kt` | `import io.bluetape4k.leader.spring.aop.LeaderElection` → `import io.bluetape4k.leader.annotation.LeaderElection` 및 `LeaderGroupElection` 동일 |
| `LeaderAopProperties.kt` | `import io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode` → `import io.bluetape4k.leader.annotation.LeaderAspectFailureMode` |
| `leader-micrometer/MicrometerLeaderAopMetricsRecorder.kt` | `import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder` → `import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder` 및 `SkipReason` 동일 |
| `leader-micrometer` 테스트 파일 | 동일 import 업데이트 |
| `additional-spring-configuration-metadata.json` | `"type": "io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode"` → `"type": "io.bluetape4k.leader.annotation.LeaderAspectFailureMode"` |

#### `leader-spring-boot`로 이동 (패키지 재편 — boot4에서)

| 원본 패키지 | 새 패키지 |
|---|---|
| `io.bluetape4k.leader.spring.boot4` | `io.bluetape4k.leader.spring` |
| `io.bluetape4k.leader.spring.boot4.adapter` | `io.bluetape4k.leader.spring.adapter` |
| `io.bluetape4k.leader.spring.boot4.aop.autoconfigure` | `io.bluetape4k.leader.spring.aop.autoconfigure` |
| `io.bluetape4k.leader.spring.boot4.backend` | `io.bluetape4k.leader.spring.backend` |
| `io.bluetape4k.leader.spring.boot4.metrics` | `io.bluetape4k.leader.spring.metrics` |

#### 클래스 이름 변경

| 구 이름 | 새 이름 | 위치 |
|---|---|---|
| `Boot4LeaderProperties` | `LeaderProperties` | `io.bluetape4k.leader.spring` |

---

## 3. 설계 결정

### 3.1 AspectJ 전략 — CTW-only (AOT 호환)

**결정: Freefair post-compile weaving (CTW) 유지, `@EnableAspectJAutoProxy` 제거**

| 항목 | 결정 |
|------|------|
| Weaving 방식 | Freefair AspectJ post-compile weaving (ajc 빌드타임 weave) |
| `@EnableAspectJAutoProxy` | **제거** — CTW와 중복 시 double-advice 발생, AOT 비호환 |
| Aspect 등록 | `@Bean`으로만 (Spring DI/lifecycle 목적, weaving 아님) |
| `open` 키워드 | 불필요 (CGLIB proxy 없음, CTW는 바이트코드 직접 주입) |
| GraalVM Native Image | 호환 (런타임 proxy 생성 없음) |
| Spring Boot 4 AOT | 호환 (빌드타임 weaving = AOT-friendly) |

**현재 `leader-spring-boot4` 코드에서 제거할 항목:**
- `LeaderAopAutoConfiguration.kt` 의 `@EnableAspectJAutoProxy` (line 51 근처)
- 이유: Freefair CTW가 이미 aspect를 bytecode에 weave. Spring proxy가 추가되면 advice가 두 번 실행됨

**유지 항목:**
- `aspect()` 설정 불필요 — `@Aspect` 클래스가 동일 모듈 main sourceset에 위치하므로 자동 weave
- Aspect `@Bean` 등록 — Spring이 의존성 주입 및 lifecycle 관리
- `LeaderAnnotationValidatorBeanPostProcessor` — aspect weaving과 무관, 별도 BPP로 유지

### 3.2 Local Factory fallback 정책

`LeaderBeanSelector`는 factory가 2개 이상일 때 `@Primary` 없으면 ambiguous 예외를 던진다.

**정책 (현재 코드 동작 유지):**
- `LocalLeaderElectionFactory`는 `@ConditionalOnMissingBean`으로 등록 → 다른 factory가 없을 때만 fallback
- 여러 factory 공존 시 `@LeaderElection(bean = "redissonLeaderElectionFactory")` 명시 필요
- `@Primary` 미설정 + `bean=""` 상태에서 2개 이상 factory → 설계 의도적 fail-fast (명시적 선택 강제)

**Spec에 추가할 내용:** 기존 동작을 그대로 유지. 변경 없음.

### 3.3 leader-micrometer 의존성 사이클 해소

기존: `leader-micrometer → leader-spring-boot-common` (Spring 의존성 전이)
개선: `leader-micrometer → leader-core` (순수 Kotlin만 의존)

`leader-micrometer/build.gradle.kts`:
```diff
-    api(project(":leader-spring-boot-common"))
+    api(project(":leader-core"))
```

### 3.3 AutoConfiguration 순서

```
AutoConfiguration.imports 등록 순서:
1. LeaderElectionAutoConfiguration      ← 전체 AutoConfig 진입점, @ConfigurationProperties 등록
2. LocalLeaderConfiguration             ← Local backend (Docker 불필요, 항상 등록)
3. LeaderAopFactoryAutoConfiguration    ← Phase 1: 백엔드별 factory @Beans
4. LeaderMicrometerAutoConfiguration    ← Phase 1.5: Micrometer recorder (Aspect보다 먼저)
5. LeaderAopAutoConfiguration           ← Phase 2: Aspect + BPP + SpEL
```

### 3.4 삭제 판단

- `LeaderAopHealthIndicator`: Boot 4의 HealthContributor API 경로 변경으로 Boot 3 전용. 별도 후속 PR(#80 범위)에서 Boot 4 호환 구현 예정.
- `LeaderElectionConfigSupport`: 상속자 전무. Boot3/4 분리 구조에서 필요했던 추상 클래스이나 통합 후 무용.

### 3.5 Breaking Changes 문서화

release 전이므로 migration guide 필요 없으나 CHANGELOG에 명시:
- `io.bluetape4k.leader.spring.aop.LeaderElection` → `io.bluetape4k.leader.annotation.LeaderElection`
- `io.bluetape4k.leader.spring.aop.LeaderGroupElection` → `io.bluetape4k.leader.annotation.LeaderGroupElection`
- `io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode` → `io.bluetape4k.leader.annotation.LeaderAspectFailureMode`
- `io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder` → `io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder`
- `io.bluetape4k.leader.spring.aop.metrics.SkipReason` → `io.bluetape4k.leader.metrics.SkipReason`
- `Boot4LeaderProperties` → `LeaderProperties`
- Maven artifact: `leader-spring-boot4` / `leader-spring-boot-common` / `leader-spring-boot3` → `leader-spring-boot`

---

## 4. leader-spring-boot 모듈 구조

```
leader-spring-boot/
├── build.gradle.kts                              (Freefair AspectJ + Spring Boot 4 BOM)
├── src/
│   ├── main/
│   │   ├── kotlin/io/bluetape4k/leader/spring/
│   │   │   ├── LeaderElectionAutoConfiguration.kt
│   │   │   ├── LeaderProperties.kt               (renamed from Boot4LeaderProperties)
│   │   │   ├── MongoCollectionProperties.kt
│   │   │   ├── adapter/
│   │   │   │   └── PropertiesAdapter.kt
│   │   │   ├── aop/
│   │   │   │   ├── LeaderAspectOrder.kt
│   │   │   │   ├── LeaderBeanSelector.kt
│   │   │   │   ├── LeaderElectionAspect.kt
│   │   │   │   ├── LeaderGroupElectionAspect.kt
│   │   │   │   ├── autoconfigure/
│   │   │   │   │   ├── LeaderAopAutoConfiguration.kt
│   │   │   │   │   └── LeaderAopFactoryAutoConfiguration.kt
│   │   │   │   ├── cache/
│   │   │   │   │   └── FactoryCacheKey.kt
│   │   │   │   ├── properties/
│   │   │   │   │   └── LeaderAopProperties.kt
│   │   │   │   ├── spel/
│   │   │   │   │   └── SpelExpressionEvaluator.kt
│   │   │   │   ├── util/
│   │   │   │   │   ├── AnnotationLookup.kt
│   │   │   │   │   ├── DurationParser.kt
│   │   │   │   │   └── LockNameValidator.kt
│   │   │   │   └── validator/
│   │   │   │       └── LeaderAnnotationValidatorBeanPostProcessor.kt
│   │   │   ├── backend/
│   │   │   │   ├── ExposedJdbcLeaderConfiguration.kt
│   │   │   │   ├── ExposedR2dbcLeaderConfiguration.kt
│   │   │   │   ├── HazelcastLeaderConfiguration.kt
│   │   │   │   ├── LettuceLeaderConfiguration.kt
│   │   │   │   ├── LocalLeaderConfiguration.kt
│   │   │   │   ├── MongoLeaderConfiguration.kt
│   │   │   │   └── RedissonLeaderConfiguration.kt
│   │   │   ├── metrics/
│   │   │   │   └── LeaderMicrometerAutoConfiguration.kt
│   │   │   └── properties/
│   │   │       ├── LeaderElectionProperties.kt
│   │   │       └── LeaderGroupProperties.kt
│   │   └── resources/META-INF/spring/
│   │       ├── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   │       └── additional-spring-configuration-metadata.json
│   └── test/
│       ├── kotlin/io/bluetape4k/leader/spring/   (boot4 테스트 패키지 rename)
│       └── resources/
│           ├── junit-platform.properties
│           └── logback-test.xml
└── README.md / README.ko.md
```

---

## 5. build.gradle.kts 스켈레톤

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.spring.boot4) apply false
    id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"
}

kover {
    reports {
        verify {
            rule {
                bound { minValue = 60 }
            }
        }
    }
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot4.dependencies.get().toString())
        mavenBom(libs.kotlin.bom.get().toString())  // kotlin-bom은 spring-boot-dependencies 후에 import
    }
    dependencies {
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
        dependency("org.mongodb:mongodb-driver-reactivestreams:${libs.versions.mongo.driver.get()}")
        dependency("com.h2database:h2:2.1.214")  // r2dbc-h2 1.0.x ABI 호환
    }
}

dependencies {
    api(project(":leader-core"))

    // 백엔드 모듈 — compileOnly (소비자가 런타임에 선택)
    compileOnly(project(":leader-redis-lettuce"))
    compileOnly(project(":leader-redis-redisson"))
    compileOnly(project(":leader-exposed-jdbc"))
    compileOnly(project(":leader-exposed-r2dbc"))
    compileOnly(project(":leader-mongodb"))
    compileOnly(project(":leader-hazelcast"))
    compileOnly(project(":leader-micrometer"))

    // Spring Boot 4 / AspectJ — CTW 방식, Spring proxy 없음
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.aop)
    api(libs.spring.expression)
    api(libs.spring.aspects)
    api(libs.aspectjweaver)
    api(libs.aspectjrt)
    compileOnly(libs.spring.boot.actuator)
    compileOnly(libs.spring.boot.configuration.processor)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)

    // SpelExpressionEvaluator cache + annotation 파라미터 이름 평가
    implementation(libs.caffeine)
    implementation(libs.kotlin.reflect)
    implementation(libs.bluetape4k.logging)

    // backend classpath guards
    compileOnly(libs.lettuce.core)
    compileOnly(libs.redisson)
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.mongodb.driver.kotlin.coroutine)
    compileOnly(libs.hazelcast)

    // 테스트
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.test.autoconfigure)
    testImplementation(libs.spring.test)
    testImplementation(libs.springmockk)
    testImplementation(project(":leader-redis-redisson"))
    testImplementation(project(":leader-redis-lettuce"))
    testImplementation(project(":leader-exposed-jdbc"))
    testImplementation(project(":leader-exposed-r2dbc"))
    testImplementation(project(":leader-mongodb"))
    testImplementation(project(":leader-micrometer"))
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(libs.r2dbc.h2)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation("org.assertj:assertj-core")
}

tasks.compileJava {
    inputs.files(tasks.processResources)
}
```

---

## 6. AutoConfiguration.imports

```
io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
io.bluetape4k.leader.spring.backend.LocalLeaderConfiguration
io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
io.bluetape4k.leader.spring.metrics.LeaderMicrometerAutoConfiguration
io.bluetape4k.leader.spring.aop.autoconfigure.LeaderAopAutoConfiguration
```

---

## 7. GitHub Workflow 변경

### ci.yml — `test-spring-boot` 잡 추가

```yaml
  test-spring-boot:
    name: Test / leader-spring-boot (Testcontainers)
    runs-on: ubuntu-latest
    needs: build
    # ... (Redis + MongoDB Testcontainers)
    env:
      TESTCONTAINERS_RYUK_DISABLED: 'true'
      DOCKER_HOST: 'unix:///var/run/docker.sock'
```

`coverage-report.needs`와 `ci-status.needs`에 `- test-spring-boot` 추가.

### nightly.yml

| 변경 | 잡 이름 |
|------|---------|
| 삭제 | `test-spring-boot-common` |
| 삭제 | `test-spring-boot3` |
| 삭제 | `test-spring-boot4` |
| 추가 | `test-spring-boot` |

`coverage-report.needs`와 `nightly-status.needs`도 동일하게 업데이트.

---

## 8. 테스트 전략

### 8.1 leader-core 신규 테스트 (⚠️ 필수 — 신규 annotation/metrics SPI 커버리지)

| 테스트 파일 | 검증 내용 |
|---|---|
| `annotation/LeaderElectionAnnotationTest.kt` | annotation attribute 기본값, `@Retention(RUNTIME)`, `@Target(FUNCTION)` |
| `annotation/LeaderGroupElectionAnnotationTest.kt` | annotation attribute 기본값 (maxLeaders default=-1 확인만, 유효성 검증은 Spring validator 담당) |
| `annotation/LeaderAspectFailureModeTest.kt` | enum values 열거 완전성 (INHERIT, RETHROW, SKIP) |
| `metrics/SkipReasonTest.kt` | enum values 열거 완전성 |
| `metrics/NoOpLeaderAopMetricsRecorderTest.kt` | NoOp 구현체 콜백 예외 없이 동작 |

> **Note**: `maxLeaders ≤ 1` 검증 (`IllegalStateException`)은 annotation 자체가 아닌 `LeaderAnnotationValidatorBeanPostProcessor`에서 수행 → `leader-spring-boot` 테스트에서 검증

### 8.2 leader-spring-boot-common 테스트 이동 (→ leader-spring-boot)

패키지 rename: `io.bluetape4k.leader.spring.aop.*` → 동일 경로 유지 (모듈만 변경)

| 원본 테스트 파일 | 처리 |
|---|---|
| `LeaderElectionAspectTest.kt` | 이동 + `@Around` pointcut 참조 업데이트 확인 |
| `LeaderGroupElectionAspectTest.kt` | 이동 + `@Around` pointcut 참조 업데이트 확인 |
| `LeaderBeanSelectorTest.kt` | 이동 |
| `LeaderAnnotationValidatorBeanPostProcessorTest.kt` | 이동 + annotation import 업데이트 |
| `LeaderAopPropertiesBindingTest.kt` | 이동 + `LeaderAspectFailureMode` import 업데이트 |
| `FactoryCacheKeyTest.kt` | 이동 (`GroupFactoryCacheKey` 테스트 포함 여부 확인) |
| `SpelExpressionEvaluatorTest.kt` | 이동 |
| `DurationParserTest.kt` | 이동 |
| `LockNameValidatorTest.kt` | 이동 |
| `LeaderElectionPropertiesTest.kt` | 이동 |
| `LeaderGroupPropertiesTest.kt` | 이동 |
| `aop/health/LeaderAopHealthIndicatorTest.kt` | **삭제** (삭제된 클래스) |
| `config/LeaderElectionConfigSupportTest.kt` | **삭제** (삭제된 클래스) |

### 8.3 leader-spring-boot4 테스트 이동 (→ leader-spring-boot)

패키지 rename: `io.bluetape4k.leader.spring.boot4.*` → `io.bluetape4k.leader.spring.*`

| 원본 테스트 파일 | 처리 |
|---|---|
| `AbstractRedissonAutoConfigurationTest.kt` | 이동 + 패키지 rename |
| `LeaderElectionAutoConfigurationTest.kt` | 이동 + 패키지 rename |
| `BackendConditionalTest.kt` | 이동 + 패키지 rename |
| `Boot4LeaderPropertiesBindingTest.kt` | **이름 변경** → `LeaderPropertiesBindingTest.kt` + `Boot4LeaderProperties` → `LeaderProperties` 내부 참조 업데이트 |
| `adapter/PropertiesAdapterTest.kt` | 이동 + 패키지 rename + `Boot4LeaderProperties` → `LeaderProperties` |
| `metrics/LeaderMicrometerAutoConfigurationBoot4Test.kt` | **이름 변경** → `LeaderMicrometerAutoConfigurationTest.kt` + 패키지 rename |

### 8.4 leader-spring-boot3 테스트 처리

| 테스트 파일 | 처리 |
|---|---|
| `LeaderMicrometerAutoConfigurationBoot3Test.kt` | **삭제** (boot3 전용) |
| 나머지 boot3 테스트 | boot4에 이미 동등한 테스트 존재 → **삭제** |

### 8.5 junit-platform.properties 표준

새 모듈 `leader-spring-boot/src/test/resources/junit-platform.properties`:
```properties
junit.jupiter.extensions.autodetection.enabled=true
junit.jupiter.testinstance.lifecycle.default=per_class

junit.jupiter.execution.parallel.enabled=false
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```

---

## 9. DoD (Definition of Done)

| 항목 | 검증 방법 |
|------|-----------|
| `leader-spring-boot-common`, `leader-spring-boot3`, `leader-spring-boot4` 디렉토리 삭제 | `git status` — 세 모듈 없음 |
| `leader-spring-boot` 모듈 빌드 성공 | `./gradlew :leader-spring-boot:build` |
| `leader-core` annotation + metrics SPI 추가 빌드 성공 | `./gradlew :leader-core:build` |
| `leader-micrometer` 의존 수정 후 테스트 통과 | `./gradlew :leader-micrometer:test` |
| 전체 프로젝트 컴파일 성공 | `./gradlew build -x test --parallel` |
| 전체 leader-spring-boot 테스트 통과 | `./gradlew :leader-spring-boot:test` |
| `settings.gradle.kts` — 3개 구 모듈 제거, 1개 신규 모듈 추가 | 파일 확인 |
| `leader-bom` 업데이트 | `./gradlew :leader-bom:build` |
| `CLAUDE.md` 모듈 목록 업데이트 | 파일 확인 |
| GitHub workflow CI + nightly 업데이트 | 파일 확인 |
| README.md + README.ko.md 업데이트 | 파일 확인 |
| Grep 잔재 확인 (코드 파일) | `rg "io\.bluetape4k\.leader\.spring\.boot[34]\|leader-spring-boot[34]\|leader-spring-boot-common\|Boot4LeaderProperties\|LeaderAopHealthIndicator\|LeaderElectionConfigSupport\|LeaderMicrometerHealthAutoConfiguration" --include="*.kt" --include="*.kts" --include="*.yml" --include="*.json" --include="*.imports" --glob="!docs/**"` → 0건 (`libs.plugins.spring.boot4`, `libs.spring.boot4.dependencies` 같은 Gradle 카탈로그 식별자는 대상 아님) |
| `@Around` pointcut FQCN 확인 | `rg "leader.spring.aop.Leader"` → `LeaderElectionAspect.kt`, `LeaderGroupElectionAspect.kt`에서 0건 |
| `additional-spring-configuration-metadata.json` FQCN | `rg "leader.spring.aop.LeaderAspectFailureMode"` → 0건 |
| Kover coverage ≥ 60% | `./gradlew :leader-spring-boot:koverVerify` |
| Step 6-R Tier 1-6 리뷰 CRITICAL/HIGH 0 | 리뷰 결과 확인 |
