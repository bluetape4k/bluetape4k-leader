# Design — leader-spring-boot3 Auto-Configuration

**작업 유형**: Type-B Fast Track
**관련 이슈**: #11
**작성일**: 2026-05-04
**브랜치**: `feat/spring-boot3-autoconfig`

---

## 1. 목표

`leader-spring-boot3` 모듈에 Spring Boot 3 자동설정 추가. classpath/Bean 조건에 따라 사용 가능한 백엔드(Redisson, Lettuce, Mongo, Hazelcast, ExposedJdbc, ExposedR2dbc)별 리더 선출 빈을 자동 등록.

## 2. 사전 검증된 제약사항 (review 반영)

| ID | 제약 | 영향 |
|----|------|------|
| C-1 | `LeaderElectionProperties` (data class) — `@ConfigurationProperties` 어노테이션 없음 | Boot3 전용 `Boot3LeaderProperties` wrapper 필요 |
| C-2 | `MongoSuspendLeaderElection.invoke` / `ExposedR2dbcSuspendLeaderElection.invoke` — `suspend` factory (스키마 초기화 포함) | `@Bean` 메서드에서 `runBlocking { ... }` 호출 |
| C-3 | `MongoSuspendLeaderGroupElection`은 `MongoCollection<Document>` + `CoroutineMongoCollection<Document>` 두 컬렉션 필요 | 두 빈 모두 요구 또는 `MongoDatabase` + 컬렉션명 properties 도입 |
| C-4 | 각 백엔드 자체 Options 클래스 (`MongoLeaderElectionOptions`, `ExposedJdbcLeaderElectionOptions`, `ExposedR2dbcLeaderElectionOptions`) | properties→백엔드 options 어댑터 함수 필요 |
| C-5 | `ExposedJdbcVirtualThreadLeaderElection`은 `ExposedJdbcLeaderElection`을 wrapping | 별도 빈으로 노출, sync 빈 의존 |
| C-6 | 백엔드 동시 활성화 시 `LeaderElection`/`SuspendLeaderElection` 타입 빈 다수 발생 | 빈 이름 기반 주입 + `@Qualifier` 정책 README 명시 |
| C-7 | `data class` `@ConfigurationProperties` 바인딩 — Spring Boot 3.5 정식 지원 | Boot3 wrapper에 `@ConfigurationProperties(prefix = "bluetape4k.leader")` 부착 |

---

## 3. 설계

### 3.1 패키지 구조

```
io.bluetape4k.leader.spring.boot3/
├── LeaderElectionAutoConfiguration.kt           # 진입점
├── Boot3LeaderProperties.kt                     # @ConfigurationProperties wrapper
├── MongoCollectionProperties.kt                 # Mongo 컬렉션명 properties
├── adapter/
│   └── PropertiesAdapter.kt                     # common Properties → 백엔드 Options
└── backend/
    ├── LocalLeaderConfiguration.kt              # default fallback
    ├── RedissonLeaderConfiguration.kt
    ├── LettuceLeaderConfiguration.kt
    ├── MongoLeaderConfiguration.kt
    ├── HazelcastLeaderConfiguration.kt
    ├── ExposedJdbcLeaderConfiguration.kt
    └── ExposedR2dbcLeaderConfiguration.kt
```

### 3.2 Properties 설계

**Boot3 wrapper (Boot 의존성을 common에 침투시키지 않기 위함)**:

```kotlin
@ConfigurationProperties(prefix = "bluetape4k.leader")
data class Boot3LeaderProperties(
    val waitTime: Duration = LeaderElectionProperties.DefaultWaitTime,
    val leaseTime: Duration = LeaderElectionProperties.DefaultLeaseTime,
    @field:NestedConfigurationProperty
    val group: LeaderGroupProperties = LeaderGroupProperties(),
    @field:NestedConfigurationProperty
    val mongo: MongoCollectionProperties = MongoCollectionProperties(),
) {
    fun toCommon(): LeaderElectionProperties =
        LeaderElectionProperties(waitTime, leaseTime, group)
}

@ConfigurationProperties(prefix = "bluetape4k.leader.mongo")
data class MongoCollectionProperties(
    val singleCollection: String = "leader_election",
    val groupCollection: String = "leader_group_election",
)
```

YAML 예시:
```yaml
bluetape4k:
  leader:
    wait-time: 5s
    lease-time: 60s
    group:
      max-leaders: 3
      wait-time: 5s
      lease-time: 60s
    mongo:
      single-collection: "leader_election"
      group-collection: "leader_group_election"
```

### 3.3 PropertiesAdapter

```kotlin
internal object PropertiesAdapter {
    fun toCommonElection(p: Boot3LeaderProperties): LeaderElectionOptions = ...
    fun toCommonGroup(p: Boot3LeaderProperties): LeaderGroupElectionOptions = ...
    fun toMongo(p: Boot3LeaderProperties): MongoLeaderElectionOptions = ...
    fun toMongoGroup(p: Boot3LeaderProperties): MongoLeaderGroupElectionOptions = ...
    fun toExposedJdbc(p: Boot3LeaderProperties): ExposedJdbcLeaderElectionOptions = ...
    fun toExposedJdbcGroup(p: Boot3LeaderProperties): ExposedJdbcLeaderGroupElectionOptions = ...
    fun toExposedR2dbc(p: Boot3LeaderProperties): ExposedR2dbcLeaderElectionOptions = ...
    fun toExposedR2dbcGroup(p: Boot3LeaderProperties): ExposedR2dbcLeaderGroupElectionOptions = ...
}
```

각 백엔드 자체 옵션 (`retryDelay`, `retryStrategy`, `recordHistory`, `lockOwner` 등)은 v1.0에서 **항상 기본값 사용**. 후속 이슈에서 노출 검토 (M-2 리스크).

### 3.4 백엔드별 빈 등록 (matrix)

| Backend | Conditional | 빈 이름 | 빈 타입 |
|---------|-------------|---------|---------|
| Redisson | `OnClass(RedissonClient)` + `OnBean(RedissonClient)` | `redissonLeaderElection` | `LeaderElection` |
| Redisson | 〃 | `redissonSuspendLeaderElection` | `SuspendLeaderElection` |
| Redisson | 〃 | `redissonLeaderGroupElection` | `LeaderGroupElection` |
| Redisson | 〃 | `redissonSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` |
| Local ⭐ | (없음 — `leader-core` 항상 포함) + `OnMissingBean(type = LeaderElection::class)` | `localLeaderElection` | `LeaderElection` (default fallback) |
| Local ⭐ | `OnMissingBean(type = SuspendLeaderElection::class)` | `localSuspendLeaderElection` | `SuspendLeaderElection` (default fallback) |
| Local ⭐ | `OnMissingBean(type = LeaderGroupElection::class)` | `localLeaderGroupElection` | `LeaderGroupElection` (default fallback) |
| Local ⭐ | `OnMissingBean(type = SuspendLeaderGroupElection::class)` | `localSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` (default fallback) |
| Lettuce | `OnClass(StatefulRedisConnection)` + `OnBean(StatefulRedisConnection)` | `lettuceLeaderElection` | `LeaderElection` |
| Lettuce | 〃 | `lettuceSuspendLeaderElection` | `SuspendLeaderElection` |
| Lettuce | 〃 | `lettuceLeaderGroupElection` | `LeaderGroupElection` |
| Lettuce | 〃 | `lettuceSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` |
| Mongo | `OnClass(MongoCollection)` + `OnBean(MongoDatabase)` | `mongoLeaderElection` | `LeaderElection` |
| Mongo | `OnClass(CoroutineMongoCollection)` + `OnBean(CoroutineMongoDatabase)` | `mongoSuspendLeaderElection` | `SuspendLeaderElection` |
| Mongo | `OnClass(MongoCollection)` + `OnBean(MongoDatabase)` | `mongoLeaderGroupElection` | `LeaderGroupElection` |
| Mongo | sync `MongoDatabase` + `CoroutineMongoDatabase` | `mongoSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` |
| Hazelcast | `OnClass(HazelcastInstance)` + `OnBean(HazelcastInstance)` | `hazelcastLeaderElection` | `LeaderElection` |
| Hazelcast | 〃 | `hazelcastSuspendLeaderElection` | `SuspendLeaderElection` |
| Hazelcast | 〃 | `hazelcastLeaderGroupElection` | `LeaderGroupElection` |
| Hazelcast | 〃 | `hazelcastSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` |
| ExposedJdbc | `OnClass(org.jetbrains.exposed.v1.jdbc.Database)` + `OnBean(Database)` | `exposedJdbcLeaderElection` | `LeaderElection` |
| ExposedJdbc | 〃 | `exposedJdbcLeaderGroupElection` | `LeaderGroupElection` |
| ExposedJdbc | 〃 + `OnBean(exposedJdbcLeaderElection)` | `exposedJdbcVirtualThreadLeaderElection` | `VirtualThreadLeaderElection` |
| ExposedR2dbc | `OnClass(R2dbcDatabase)` + `OnBean(R2dbcDatabase)` | `exposedR2dbcSuspendLeaderElection` | `SuspendLeaderElection` |
| ExposedR2dbc | 〃 | `exposedR2dbcSuspendLeaderGroupElection` | `SuspendLeaderGroupElection` |

총 빈 수: Local 4 (default fallback) + Redisson 4 + Lettuce 4 + Mongo 4 + Hazelcast 4 + ExposedJdbc 3 + ExposedR2dbc 2 = **25**. 단 Local은 다른 백엔드 빈이 없을 때만 등록.

각 빈 `@ConditionalOnMissingBean(name = "...")` 적용 → 사용자 override 가능. Local은 추가로 `@ConditionalOnMissingBean(type = ...)` 적용으로 다른 백엔드 활성 시 자동 비활성.

### 3.5 Mongo 설계 변경 (review 반영)

기존: `OnBean(MongoCollection<Document>)` 단일 게이트. → 너무 느슨, 컬렉션명 모호.
변경: `OnBean(MongoDatabase)` 게이트 + `MongoCollectionProperties.singleCollection`/`groupCollection`로 컬렉션 이름 지정. 빈 메서드 내부에서:
```kotlin
@Bean
fun mongoLeaderElection(db: MongoDatabase, props: Boot3LeaderProperties) =
    MongoLeaderElection(
        db.getCollection(props.mongo.singleCollection, Document::class.java),
        PropertiesAdapter.toMongo(props),
    )
```

### 3.6 Suspend factory 처리 (C-2)

```kotlin
@Bean
fun mongoSuspendLeaderElection(
    coroutineDb: CoroutineMongoDatabase,
    props: Boot3LeaderProperties,
): MongoSuspendLeaderElection = runBlocking {
    MongoSuspendLeaderElection(
        coroutineDb.getCollection(props.mongo.singleCollection, Document::class.java),
        PropertiesAdapter.toMongo(props),
    )
}
```

`runBlocking`은 startup phase에서만 호출되므로 안전. 트레이드오프 README에 명시.

### 3.7 진입점

```kotlin
@AutoConfiguration
@ConditionalOnClass(LeaderElection::class)
@EnableConfigurationProperties(Boot3LeaderProperties::class, MongoCollectionProperties::class)
@Import(
    RedissonLeaderConfiguration::class,
    LettuceLeaderConfiguration::class,
    MongoLeaderConfiguration::class,
    HazelcastLeaderConfiguration::class,
    ExposedJdbcLeaderConfiguration::class,
    ExposedR2dbcLeaderConfiguration::class,
    LocalLeaderConfiguration::class,  // 마지막 — `@AutoConfigureOrder` LOWEST_PRECEDENCE로 다른 백엔드 빈 등록 후 평가
)
class LeaderElectionAutoConfiguration
```

`AutoConfiguration.imports`:
```
io.bluetape4k.leader.spring.boot3.LeaderElectionAutoConfiguration
```

### 3.8 다중 백엔드 정책 (C-6) — Local default fallback

- **default backend**: `Local` (in-memory, `leader-core`). 외부 인프라 없이 동작. dev/test 즉시 사용
- Local 빈은 `@ConditionalOnMissingBean(type = LeaderElection::class)` 등으로 등록 → **다른 백엔드 빈이 등장하면 자동 비활성**
- 빈 이름은 모두 다르므로 Spring 컨테이너 등록 충돌 없음
- 단일 백엔드 활성 시 (예: Lettuce만) → 사용자 `@Autowired LeaderElection` 자동 주입
- **다중 백엔드** 동시 활성 시 (예: Lettuce + Redisson) → `NoUniqueBeanDefinitionException` 발생 가능 → README에 `@Qualifier("lettuceLeaderElection")` 명시 가이드
- v1.0에서 `default-backend` 속성/`@Primary`는 도입하지 않음 (YAGNI). Local fallback으로 dev 환경 커버, 단일 prod 백엔드는 자동 주입, 다중은 명시적 qualifier

---

## 4. 테스트 전략

| 테스트 | 검증 |
|--------|------|
| `LeaderElectionAutoConfigurationTest` | `@SpringBootTest` + Redisson Testcontainer + 4 종 election 빈 주입 + 실제 `runIfLeader()` 동작 |
| `BackendConditionalTest` | `ApplicationContextRunner` × 6 백엔드 — 클라이언트 빈 미설정 시 election 빈 미등록 + 등록 시 21 빈 모두 활성 |
| `Boot3LeaderPropertiesBindingTest` | yaml `bluetape4k.leader.*` 바인딩 검증 (waitTime, group, mongo 컬렉션명) |
| `PropertiesAdapterTest` | properties → 8 종 backend Options 변환 검증 |

**커버리지 목표**: line coverage > 80% (Kover).

---

## 5. 변경 파일 (예상 18개)

| 파일 | 종류 |
|------|------|
| `gradle/libs.versions.toml` | 수정 (필요 시 추가 alias) |
| `leader-spring-boot3/build.gradle.kts` | 수정 (common api 의존 + 6 백엔드 모듈 testRuntimeOnly 추가) |
| `Boot3LeaderProperties.kt` | 신규 |
| `MongoCollectionProperties.kt` | 신규 |
| `adapter/PropertiesAdapter.kt` | 신규 |
| `LeaderElectionAutoConfiguration.kt` | 신규 |
| `backend/RedissonLeaderConfiguration.kt` | 신규 |
| `backend/LettuceLeaderConfiguration.kt` | 신규 |
| `backend/MongoLeaderConfiguration.kt` | 신규 |
| `backend/HazelcastLeaderConfiguration.kt` | 신규 |
| `backend/ExposedJdbcLeaderConfiguration.kt` | 신규 |
| `backend/ExposedR2dbcLeaderConfiguration.kt` | 신규 |
| `META-INF/spring/.../AutoConfiguration.imports` | 신규 |
| `LeaderElectionAutoConfigurationTest.kt` | 신규 |
| `AbstractRedissonAutoConfigurationTest.kt` | 신규 |
| `BackendConditionalTest.kt` | 신규 |
| `Boot3LeaderPropertiesBindingTest.kt` | 신규 |
| `PropertiesAdapterTest.kt` | 신규 |
| `src/test/resources/junit-platform.properties` | 신규 |
| `src/test/resources/logback-test.xml` | 신규 |
| `leader-spring-boot3/README.md` + `README.ko.md` | 신규 |

---

## 6. DoD (#11)

- [x] AutoConfiguration 클래스 + 6 백엔드 분리
- [x] LeaderProperties (Boot3 wrapper, common 재사용)
- [x] `META-INF/spring/AutoConfiguration.imports` 등록
- [x] `@SpringBootTest` 통합 테스트 (Redisson 백엔드)
- [x] Kover 라인 커버리지 > 80%

---

## 7. 리스크/노트

| ID | 리스크 | 완화 |
|----|--------|------|
| R-1 | `runBlocking` 호출이 startup latency 증가 (Mongo/R2DBC suspend 빈) | 컨텍스트 초기화 시 1회만 호출, README에 명시 |
| R-2 | 백엔드별 옵션 (`retryDelay` 등) 노출 안 됨 | v1.0 한계 명시. 후속 이슈로 분리 |
| R-3 | Mongo `MongoDatabase` 빈 가정 — 사용자가 직접 컬렉션 빈만 등록한 경우 미등록 | README에 "MongoDatabase 빈 필요" 명시 + 후속 옵션 검토 |
| R-4 | Spring Boot 3.5 미만 환경에서 `data class` properties 바인딩 실패 가능 | `dependencyManagement`로 spring-boot 3.5.14 강제 |
| R-5 | 다중 백엔드 활성화 시 `@Autowired LeaderElection` 사용자 코드에서 ambiguity | README의 `@Qualifier` 사용 가이드 |
