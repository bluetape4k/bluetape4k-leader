# Plan — leader-spring-boot3 Auto-Configuration

**Design**: `docs/superpowers/specs/2026-05-04-leader-spring-boot3-design.md`
**관련 이슈**: #11
**브랜치**: `feat/spring-boot3-autoconfig`
**작성일**: 2026-05-04

---

## Phase 1 — 빌드 의존성 정비

| # | 파일 | 변경 |
|---|------|------|
| 1 | `gradle/libs.versions.toml` | 필요 시 추가 alias (검증 단계) |
| 2 | `leader-spring-boot3/build.gradle.kts` | `api(project(":leader-spring-boot-common"))` 추가 + 6 백엔드 모듈 testRuntimeOnly 추가 (Kover 커버리지 측정용) |

검증: `./gradlew :leader-spring-boot3:dependencies` + `compileKotlin`

---

## Phase 2 — Properties + Adapter

| # | 파일 | 내용 |
|---|------|------|
| 3 | `Boot3LeaderProperties.kt` | `@ConfigurationProperties(prefix = "bluetape4k.leader")` data class. waitTime/leaseTime/group/mongo. `toCommon()` 변환 메서드 |
| 4 | `MongoCollectionProperties.kt` | `singleCollection: String = "leader_election"`, `groupCollection: String = "leader_group_election"` |
| 5 | `adapter/PropertiesAdapter.kt` | 8개 변환 함수 (common, group, mongo, mongoGroup, exposedJdbc, exposedJdbcGroup, exposedR2dbc, exposedR2dbcGroup) |

검증: 컴파일 통과

---

## Phase 3 — 백엔드 Configuration (7개)

> 패키지: `io.bluetape4k.leader.spring.boot3.backend`
> 공통: `@AutoConfiguration` + 클래스/빈 게이트 + `@ConditionalOnMissingBean(name = "...")` per `@Bean`
> Local은 추가로 type 기반 `@ConditionalOnMissingBean` (다른 백엔드 빈 미존재 시만 등록)

### 3.0 Local — default fallback (Phase #5.5)

```kotlin
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
class LocalLeaderConfiguration {
    @Bean @ConditionalOnMissingBean(LeaderElection::class)
    fun localLeaderElection(props: Boot3LeaderProperties) =
        LocalLeaderElection(PropertiesAdapter.toCommonElection(props))
    @Bean @ConditionalOnMissingBean(SuspendLeaderElection::class)
    fun localSuspendLeaderElection(props: Boot3LeaderProperties) =
        LocalSuspendLeaderElection(PropertiesAdapter.toCommonElection(props))
    @Bean @ConditionalOnMissingBean(LeaderGroupElection::class)
    fun localLeaderGroupElection(props: Boot3LeaderProperties) =
        LocalLeaderGroupElection(PropertiesAdapter.toCommonGroup(props))
    @Bean @ConditionalOnMissingBean(SuspendLeaderGroupElection::class)
    fun localSuspendLeaderGroupElection(props: Boot3LeaderProperties) =
        LocalSuspendLeaderGroupElection(PropertiesAdapter.toCommonGroup(props))
}
```

외부 의존 없음 (`leader-core`는 `api` 의존). `@AutoConfigureOrder(LOWEST_PRECEDENCE)`로 다른 백엔드가 먼저 평가되도록 보장.

### 3.1 Redisson (Phase #6)

```kotlin
@AutoConfiguration
@ConditionalOnClass(RedissonClient::class)
@ConditionalOnBean(RedissonClient::class)
class RedissonLeaderConfiguration {
    @Bean @ConditionalOnMissingBean(name = "redissonLeaderElection")
    fun redissonLeaderElection(client: RedissonClient, props: Boot3LeaderProperties) =
        RedissonLeaderElection(client, PropertiesAdapter.toCommonElection(props))
    // ... 나머지 3개
}
```

### 3.2 Lettuce (Phase #7)
- ctor: `(StatefulRedisConnection<String, String>, LeaderElectionOptions)`
- 4 빈 (sync/suspend × single/group)
- raw 제네릭 주입 — `@SuppressWarnings("UNCHECKED_CAST")` 또는 wildcard 처리

### 3.3 Mongo (Phase #8)
- 게이트: `OnBean(MongoDatabase)` (sync) + `OnBean(CoroutineMongoDatabase)` (suspend)
- 빈 메서드 내부 `db.getCollection(props.mongo.singleCollection, Document::class.java)`
- suspend 빈은 `runBlocking { ... }`
- group suspend는 sync + coroutine 컬렉션 둘 다 필요

### 3.4 Hazelcast (Phase #9)
- 게이트: `OnBean(HazelcastInstance)`
- 4 빈, 자체 옵션 없음 → 공통 `LeaderElectionOptions`/`LeaderGroupElectionOptions` 그대로 사용

### 3.5 ExposedJdbc (Phase #10)
- 게이트: `OnBean(org.jetbrains.exposed.v1.jdbc.Database)`
- 3 빈: sync, group, virtualThread (sync 빈을 wrapping)
- virtualThread 빈은 `@DependsOn("exposedJdbcLeaderElection")` 또는 직접 ctor 주입

### 3.6 ExposedR2dbc (Phase #11)
- 게이트: `OnBean(R2dbcDatabase)`
- 2 빈: suspend, group suspend
- 둘 다 `runBlocking { ... }` (스키마 초기화)

---

## Phase 4 — 진입점 + Imports

| # | 파일 | 내용 |
|---|------|------|
| 12 | `LeaderElectionAutoConfiguration.kt` | `@AutoConfiguration` + `@ConditionalOnClass(LeaderElection::class)` + `@EnableConfigurationProperties(Boot3LeaderProperties::class, MongoCollectionProperties::class)` + `@Import(...7개 — Local 포함...)` |
| 13 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | `LeaderElectionAutoConfiguration` FQCN |

검증: `./gradlew :leader-spring-boot3:compileKotlin`

---

## Phase 5 — 테스트 인프라

| # | 파일 | 내용 |
|---|------|------|
| 14 | `src/test/resources/junit-platform.properties` | `lifecycle.default = per_class` + `parallel.enabled = false` (MEMORY 표준) |
| 15 | `src/test/resources/logback-test.xml` | 표준 logback |
| 16 | `AbstractRedissonAutoConfigurationTest.kt` | Testcontainer redis:7-alpine 관리 base class |

---

## Phase 6 — 테스트 작성

| # | 파일 | 검증 |
|---|------|------|
| 17 | `PropertiesAdapterTest.kt` | 8 종 변환 함수 — properties → backend Options 매핑 |
| 18 | `Boot3LeaderPropertiesBindingTest.kt` | `@SpringBootTest(properties=[...])` — yaml 바인딩 (waitTime, group.maxLeaders, mongo.singleCollection 등) |
| 19 | `BackendConditionalTest.kt` | `ApplicationContextRunner` × 7 백엔드 — (a) 백엔드 빈 미설정 시 Local 4 빈만 활성 (b) 단일 백엔드 활성 시 해당 빈 + Local 비활성 (c) 다중 백엔드 활성 매트릭스 |
| 20 | `LeaderElectionAutoConfigurationTest.kt` | `@SpringBootTest` + Redisson Testcontainer + 4 종 election 빈 주입 + `runIfLeader()` 동작 |

---

## Phase 7 — 검증 + 커버리지

| # | 작업 |
|---|------|
| 21 | `./gradlew :leader-spring-boot3:test` — 전체 통과 |
| 22 | `./gradlew :leader-spring-boot3:koverHtmlReport` — 라인 커버리지 ≥ 80% 확인 |
| 23 | 미달 시 추가 테스트 → Phase 6 재진입 |

---

## Phase 8 — 문서화

| # | 파일 | 내용 |
|---|------|------|
| 24 | `leader-spring-boot3/README.md` | 영문 — 사용법, 의존성, 자동 등록 빈 매트릭스 (Local default + 6 백엔드), yaml 설정 예시, 다중 백엔드 시 `@Qualifier` 가이드, `runBlocking` 트레이드오프, Mongo 컬렉션 properties |
| 25 | `leader-spring-boot3/README.ko.md` | 한글 동일 |

---

## 의존성 그래프

```
Phase 1 → Phase 2 → Phase 3 (6개 sub-phase 순차) → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8
```

엄격 순차. Phase 3의 6 sub-phase는 서로 독립이지만 같은 패턴 반복이라 순차 진행.

---

## 작업량 추정

- 신규 파일: 24개 (Local Configuration 추가)
- 수정 파일: 2개
- 코드 라인: ~1300 lines (구현 ~650, 테스트 ~650)
- 예상 시간: 3-4시간

---

## 리스크 / 완화 (design.md §7과 정렬)

| 리스크 | 완화 |
|--------|------|
| R-1 `runBlocking` startup latency | startup phase 1회만, README 명시 |
| R-2 백엔드 옵션 노출 부족 | v1.0 한계 명시, 후속 이슈 |
| R-3 Mongo `MongoDatabase` 빈 가정 | README "MongoDatabase 빈 필요" 명시 |
| R-4 Boot 3.5 미만 환경 data class binding | dependencyManagement로 3.5.14 강제 |
| R-5 다중 백엔드 ambiguity | README `@Qualifier` 가이드 |
| R-6 Kover 커버리지 미달 | testRuntimeOnly 6 모듈 + ApplicationContextRunner로 모든 conditional path 커버 |
