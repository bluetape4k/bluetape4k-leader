# leader-spring-boot3

[bluetape4k-leader](../README.ko.md)의 Spring Boot 3 자동 구성 모듈.
Spring 컨텍스트에 등록된 백엔드 클라이언트 빈에 따라 `LeaderElection` / `SuspendLeaderElection` /
`LeaderGroupElection` / `SuspendLeaderGroupElection` 빈을 자동 등록합니다.

## AOP 빠른 시작 (`@LeaderElection`)

Spring 관리 `open` 메서드에 `@LeaderElection` 또는 `@LeaderGroupElection`을 추가하면 됩니다.
AOP 어드바이스는 백엔드 빈과 함께 자동 구성되므로 추가 설정이 필요 없습니다.

```kotlin
@Service
class ScheduledJobService {
    @Scheduled(cron = "0 0 2 * * *")
    @LeaderElection(name = "nightly-settlement")
    open fun nightlySettlement() {
        // 클러스터에서 단 한 노드에서만 실행됩니다
    }

    // SpEL을 이용한 동적 락 이름
    @LeaderElection(name = "'process-' + #region", failureMode = LeaderAspectFailureMode.SKIP)
    open fun processRegion(region: String): Result? = service.process(region)

    // 복수 리더 (세마포어 기반): 최대 3개 동시 리더
    @LeaderGroupElection(name = "batch-shard", maxLeaders = 3)
    open fun batchShard() { ... }
}
```

**`name`에 대한 SpEL 규칙** — 전체 내용은 [leader-spring-boot-common](../leader-spring-boot-common/README.ko.md#aop-어노테이션)을 참조하세요.

### 어드바이스 순서

Leader Aspect는 `Ordered.HIGHEST_PRECEDENCE + 100`에서 실행됩니다 — 기본적으로 가장 바깥쪽.

| Aspect | Order |
|--------|-------|
| `LeaderElectionAspect` | `HIGHEST_PRECEDENCE + 100` |
| `@CircuitBreaker` (Resilience4j) | `LOWEST_PRECEDENCE - 4` |
| `@Retry` (Resilience4j) | `LOWEST_PRECEDENCE - 3` |
| `@Transactional` | `LOWEST_PRECEDENCE` |

### AOP 설정

```yaml
bluetape4k:
  leader:
    aop:
      enabled: true               # 기본값 true
      strict: false               # strict = true → 풋건 발견 시 시작 실패
      failure-mode: RETHROW       # RETHROW (기본값) 또는 SKIP
      default-wait-time: PT5S
      default-lease-time: PT1M
      lock-name-prefix: ""        # 기본값: "${spring.application.name}:"
      spel:
        allow-method-invocation: false
```

---

## 백엔드 빠른 시작

### 1. 의존성 추가

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:leader-spring-boot3:<version>")
    // 사용할 백엔드 1개 이상:
    implementation("io.github.bluetape4k.leader:leader-redis-lettuce:<version>")
    implementation("io.github.bluetape4k.leader:leader-redis-redisson:<version>")
    implementation("io.github.bluetape4k.leader:leader-mongodb:<version>")
    implementation("io.github.bluetape4k.leader:leader-hazelcast:<version>")
    implementation("io.github.bluetape4k.leader:leader-exposed-jdbc:<version>")
    implementation("io.github.bluetape4k.leader:leader-exposed-r2dbc:<version>")
}
```

백엔드 모듈이 classpath에 없으면 **Local (in-memory) fallback** 빈이 자동 등록됩니다.
외부 인프라 없이 dev / test 환경에서 즉시 동작합니다.

### 2. 백엔드 클라이언트 빈 제공

```kotlin
@Configuration
class RedisConfig {
    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient =
        Redisson.create(Config().apply { useSingleServer().setAddress("redis://localhost:6379") })
}
```

### 3. 주입 후 사용

```kotlin
@Service
class CronJobService(private val leader: LeaderElection) {
    @Scheduled(cron = "0 */15 * * * *")
    fun runIfPrimary() {
        leader.runIfLeader("cron-job") {
            // 단일 노드에서만 실행됨
        }
    }
}
```

## 빈 등록 매트릭스

| 백엔드        | 활성화 조건                                               | 등록 빈                                                                                                                                       |
|--------------|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **Local** ⭐ | 다른 백엔드 빈이 없을 때 (fallback)                       | `localLeaderElection`, `localSuspendLeaderElection`, `localLeaderGroupElection`, `localSuspendLeaderGroupElection`                            |
| Redisson     | `RedissonClient` 빈 존재                                  | `redissonLeaderElection`, `redissonSuspendLeaderElection`, `redissonLeaderGroupElection`, `redissonSuspendLeaderGroupElection`                |
| Lettuce      | `StatefulRedisConnection<String, String>` 빈 존재         | `lettuceLeaderElection`, `lettuceSuspendLeaderElection`, `lettuceLeaderGroupElection`, `lettuceSuspendLeaderGroupElection`                    |
| Mongo        | `MongoDatabase` 또는 `CoroutineMongoDatabase` 빈 존재    | `mongoLeaderElection`, `mongoLeaderGroupElection` (sync); `mongoSuspendLeaderElection`, `mongoSuspendLeaderGroupElection` (coroutine)         |
| Hazelcast    | `HazelcastInstance` 빈 존재                               | `hazelcastLeaderElection`, `hazelcastSuspendLeaderElection`, `hazelcastLeaderGroupElection`, `hazelcastSuspendLeaderGroupElection`            |
| ExposedJdbc  | `org.jetbrains.exposed.v1.jdbc.Database` 빈 존재          | `exposedJdbcLeaderElection`, `exposedJdbcLeaderGroupElection`, `exposedJdbcVirtualThreadLeaderElection`                                       |
| ExposedR2dbc | `org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase` 빈 존재                                   | `exposedR2dbcSuspendLeaderElection`, `exposedR2dbcSuspendLeaderGroupElection`                                                                 |

⭐ Local 빈은 `@ConditionalOnMissingBean(<type>::class)` 으로 등록되므로, 동일 타입의 다른 백엔드 빈이 등장하면 자동 비활성됩니다.

## 설정

YAML prefix: `bluetape4k.leader`.

```yaml
bluetape4k:
  leader:
    wait-time: 5s          # 기본 5초
    lease-time: 60s        # 기본 60초
    group:
      max-leaders: 2       # 기본 2
      wait-time: 5s
      lease-time: 60s
    mongo:
      single-collection: leader_election        # 기본
      group-collection:  leader_group_election  # 기본
```

## 다중 백엔드 사용

여러 백엔드가 동시에 활성화될 때(예: Lettuce + Redisson) 빈 이름이 모두 다르므로 컨테이너 등록 충돌은 없습니다.
하지만 `@Autowired LeaderElection` 타입 주입은 모호해져 Spring이 `NoUniqueBeanDefinitionException`을 던집니다.

`@Qualifier`로 명시:

```kotlin
@Service
class MyService(
    @Qualifier("lettuceLeaderElection") private val leader: LeaderElection,
)
```

## 사용자 빈 override

각 백엔드 빈은 `@ConditionalOnMissingBean(name = "<beanName>")`을 사용합니다. 동일 이름의 빈을
직접 `@Configuration`에 등록하면 자동 빈을 우회합니다:

```kotlin
@Bean(name = ["redissonLeaderElection"])
fun customRedissonLeader(client: RedissonClient): LeaderElection =
    RedissonLeaderElection(client, LeaderElectionOptions(waitTime = 10.seconds, leaseTime = 5.minutes))
```

## 노트 / 트레이드오프

- **시동 시점 `runBlocking`** — Mongo `suspend` 빈과 ExposedR2dbc `suspend` 빈은 `suspend operator fun invoke()`를
  Spring 의 동기 `@Bean` 계약에 맞추기 위해 `runBlocking`으로 감싸 호출합니다. 스키마 초기화는 컨텍스트 시동 시
  1회만 수행되며 요청 처리 latency에는 영향을 주지 않습니다.
- **Mongo 백엔드 요구사항** — `mongoLeaderElection`은 `MongoDatabase` (sync 드라이버), `mongoSuspendLeaderElection`은
  `CoroutineMongoDatabase` (코루틴 드라이버), `mongoSuspendLeaderGroupElection`은 **둘 다** (동일 namespace) 필요.
- **백엔드 고유 옵션** — `retryDelay`, `retryStrategy`, `recordHistory`, `lockOwner` 등은 v1.0에서 properties로
  노출되지 않으며 백엔드 기본값을 사용합니다. 후속 이슈에서 노출 검토 예정.

## 아키텍처

`LeaderElectionAutoConfiguration`이 진입점(`@AutoConfiguration`)이며 `@ConditionalOnClass(LeaderElection::class)`으로
게이트됩니다. 7개 백엔드 `@Configuration` 클래스를 `@Import`하며 각각 자체 `@ConditionalOnClass` + `@ConditionalOnBean`
조건으로 활성화됩니다. `LocalLeaderConfiguration`은 `@AutoConfigureOrder(LOWEST_PRECEDENCE)`로 선언되며
`@ConditionalOnMissingBean(<type>)`을 사용하여 다른 백엔드가 동일 타입 빈을 만들지 않을 때만 활성화됩니다.

## 관련 문서

- [leader-spring-boot-common](../leader-spring-boot-common/README.ko.md) — AOP 어노테이션, SpEL 가이드, 시작 시 검증, 실패 모드
- [프로젝트 루트 README](../README.ko.md)
