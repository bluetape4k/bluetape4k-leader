# Lessons Learned — leader-spring-boot3 자동 구성 (2026-05-04)

**관련 PR**: #70
**관련 이슈**: #11
**영향 모듈**: `leader-spring-boot3`, `leader-spring-boot-common`, `gradle/libs.versions.toml`, root `build.gradle.kts`, `CLAUDE.md`

## L1: Spring Boot BOM이 `kotlin.version=1.9.25`를 강제

### 문제
`io.spring.dependency-management` 플러그인 + `mavenBom("spring-boot-dependencies")` 사용 시 Spring Boot 3.5 BOM 의 `<kotlin.version>1.9.25</kotlin.version>` 제약이 **모든 configuration**(특히 `kotlinCompilerClasspath`)에 전파된다. 결과:
- `kotlin-compiler-embeddable:2.3.21 → 1.9.25` 다운그레이드
- `kotlin-build-tools-impl:2.3.21`이 expecting `ClasspathEntrySnapshotter$Settings` (2.3.x 신클래스) 호출 → `NoClassDefFoundError`
- 컴파일 자체가 불가능

### 교훈
Spring Dependency Management 플러그인 사용 모듈에서는 `dependencyManagement.imports.mavenBom`의 **순서가 중요**하다. spring-boot-dependencies 직후 `mavenBom(libs.kotlin.bom.get())`을 다시 import하면 kotlin 버전을 프로젝트 표준으로 복원 가능. 동일 패턴이 mongodb-driver-core/reactivestreams (5.5.x → 5.6.x) 등 다른 lib에도 적용됨. 새 Spring Boot 모듈 추가 시 무조건 적용.

```kotlin
dependencyManagement {
    imports {
        mavenBom(libs.spring.boot3.dependencies.get().toString())
        mavenBom(libs.kotlin.bom.get().toString())  // ← override Spring's pin
    }
    dependencies {
        // 백엔드 driver-core 등 spring-boot BOM이 다운그레이드시키는 항목 명시
        dependency("org.mongodb:mongodb-driver-core:${libs.versions.mongo.driver.get()}")
    }
}
```

---

## L2: `@AutoConfigureOrder`는 `@Import` 된 `@Configuration`에 무효

### 문제
초기 설계는 `LocalLeaderConfiguration`을 `@Import`로 진입점 `LeaderElectionAutoConfiguration`에 가져오면서 `@AutoConfigureOrder(LOWEST_PRECEDENCE)`로 fallback 순서를 보장하려 시도. 동작하지 않음 — `@AutoConfigureOrder`/`@AutoConfigureAfter`는 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 직접 등록된 auto-configuration에만 적용된다. `@Import`로 들여온 클래스에는 무시됨.

### 교훈
Auto-configuration 우선순위/순서가 필요한 Configuration은 **반드시 별도 auto-config로 분리하여 `AutoConfiguration.imports`에 등록**해야 한다. 같은 진입점 안의 `@Import`로는 순서 보장이 불가능. fallback 패턴 적용 시:
1. fallback Configuration 별도 파일/클래스
2. `@AutoConfiguration(after = [MainConfig::class])` 명시
3. `META-INF/spring/...AutoConfiguration.imports`에 둘 다 등록
4. `@ConditionalOnMissingBean(<type>::class)`로 활성화 조건

테스트 시 `ApplicationContextRunner.withConfiguration(AutoConfigurations.of(...))`도 두 클래스 모두 명시 필요.

---

## L3: Spring `Binder`/`@ConfigurationProperties`에는 Kotlin reified 확장이 없음

### 문제
`Binder.bind("prefix", Boot3LeaderProperties::class.java)` 처럼 `Class<T>` 인자를 받는 API. Kotlin reified 버전이 stock Spring Boot에 제공되지 않음.

### 교훈
프로젝트별 inline 확장 함수로 보강:
```kotlin
private inline fun <reified T : Any> Binder.bindAs(name: String): BindResult<T> =
    bind(name, T::class.java)

// usage
Binder(source).bindAs<Boot3LeaderProperties>("bluetape4k.leader").get()
```

반면 `BeanFactory.getBean(Class<T>)`, `ListableBeanFactory.getBeanNamesForType(Class<T>)`는 Spring이 이미 제공 (`org.springframework.beans.factory.getBean`, `getBeanNamesForType`). import 후 reified 호출:
```kotlin
ctx.getBean<LeaderElection>()
ctx.getBeanNamesForType<SuspendLeaderElection>()
```

---

## L4: `ApplicationContextRunner.run` 콜백이 AssertJ에 의존

### 문제
`spring-boot-test`만 `testImplementation`에 추가하면 `ApplicationContextRunner.run { ctx -> ... }` 호출 시 `Cannot access 'org.assertj.core.api.AssertProvider' which is a supertype of 'AssertableApplicationContext'` 컴파일 오류 발생. spring-boot-test가 AssertJ를 transitive로 가져오지만 testCompileClasspath에 포함시키지 않음.

### 교훈
`ApplicationContextRunner` 사용 모듈은 `testImplementation("org.assertj:assertj-core")`를 명시 추가 필요. 한 줄 주석으로 이유 명시:
```kotlin
// ApplicationContextRunner.run 콜백이 AssertJ AssertProvider 를 노출하므로 필수
testImplementation("org.assertj:assertj-core")
```

---

## L5: 진입점 suspend factory + Spring `@Bean` 동기 계약

### 문제
`MongoSuspendLeaderElection.invoke(...)`, `ExposedR2dbcSuspendLeaderElection.invoke(...)` 등 일부 백엔드의 인스턴스 생성 factory는 `suspend operator fun invoke`로 정의되며 내부에서 스키마 초기화 (`MongoSuspendLock.ensureIndexes`, `ExposedR2dbcSchemaInitializer.ensureSchema`) 를 수행. Spring의 `@Bean` 메서드는 동기 시그니처만 허용.

### 교훈
Startup phase에서 `runBlocking { ... }` 사용은 정당. 부모 코루틴 컨텍스트가 없으므로 `CancellationException` 재throw도 strictly 필요하지 않음 (취소 trigger 부재). 단 README 트레이드오프 명시 필수: "스키마 초기화는 컨텍스트 시동 시 1회만 수행, 요청 latency에는 영향 없음".

대안 검토:
- `SmartLifecycle.start()`에서 초기화 (지연 초기화) — 빈 즉시 사용 불가
- `InitializingBean.afterPropertiesSet()`도 동기 — 동일 문제
- `@PostConstruct` + `runBlocking` — 동등

→ `@Bean` 메서드 내 `runBlocking`이 가장 직관적.

---

## L6: `@ConfigurationProperties` data class wrapper의 가치

### 문제
초기 검토에서 "common의 `LeaderElectionProperties`에 `@ConfigurationProperties` 직접 부착하면 Boot3 wrapper 불필요" 검토. 사용자는 wrapper 유지 결정.

### 교훈
**Boot3LeaderProperties wrapper 유지의 이점**:
- common 모듈이 spring-boot 의존 없이 순수 Kotlin 유지
- Boot 3 / Boot 4 별 prefix/명명 차별화 가능
- mongo collection 같은 backend-specific properties를 wrapper에서 추가 가능 (common 오염 방지)
- 변환 layer (PropertiesAdapter) 도입으로 backend-specific 옵션 매핑 명시화

비용: data class duplication 1개 + `toCommon()` (사용처가 PropertiesAdapter로 흡수되면 dead code → 제거함).

---

## L7: Kover 라인 커버리지 100% 달성 — `ApplicationContextRunner` × backend-per-Configuration 패턴이 효과적

### 문제
초기 PropertiesAdapterTest + Redisson 통합 테스트만으로 line coverage 55%. Mongo/ExposedJdbc/ExposedR2dbc Configuration은 미커버.

### 교훈
백엔드별 Configuration이 모두 비슷한 pattern (백엔드 client 빈 → 4개 election 빈 등록) 이라 `ApplicationContextRunner` × per-backend 통합 테스트 1개씩 추가만으로 커버리지 폭발적 증가:
- `ExposedJdbc`: H2 in-memory `Database.connect()` 빈만 등록
- `ExposedR2dbc`: H2 R2DBC `R2dbcDatabase.connect()` 빈만 등록 (단 r2dbc-h2와 h2-v2 ABI 충돌 주의 — r2dbc-h2 단독 사용)
- `Mongo`: `MongoDBServer.Launcher.mongoDB` Testcontainer + `MongoDBServer.Launcher.getClient()/getCoroutineClient()` 활용

각 테스트는 `containsBean("...")` 또는 `getBean<X>()` 정도만 검증해도 Configuration의 모든 `@Bean` 메서드가 호출되어 line coverage가 채워짐.
