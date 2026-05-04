# leader-spring-boot3

Spring Boot 3 auto-configuration for [bluetape4k-leader](../README.md).
Auto-registers `LeaderElection` / `SuspendLeaderElection` / `LeaderGroupElection` / `SuspendLeaderGroupElection` beans
based on the backend client beans available in your Spring context.

## AOP Quick Start (`@LeaderElection`)

Add `@LeaderElection` or `@LeaderGroupElection` to any Spring-managed `open` method.
The AOP advice is auto-configured alongside the backend beans — no extra configuration needed.

```kotlin
@Service
class ScheduledJobService {
    @Scheduled(cron = "0 0 2 * * *")
    @LeaderElection(name = "nightly-settlement")
    open fun nightlySettlement() {
        // executes on only one node in the cluster
    }

    // Dynamic lock name via SpEL
    @LeaderElection(name = "'process-' + #region", failureMode = LeaderAspectFailureMode.SKIP)
    open fun processRegion(region: String): Result? = service.process(region)

    // Multi-leader (semaphore-based): up to 3 concurrent leaders
    @LeaderGroupElection(name = "batch-shard", maxLeaders = 3)
    open fun batchShard() { ... }
}
```

**SpEL rules for `name`** — see [leader-spring-boot-common](../leader-spring-boot-common/README.md#aop-annotations) for full reference.

### Advice Order

Leader Aspect runs at `Ordered.HIGHEST_PRECEDENCE + 100` — outermost by default, inside any user-defined aspects above it.

| Aspect | Order |
|--------|-------|
| `LeaderElectionAspect` | `HIGHEST_PRECEDENCE + 100` |
| `@CircuitBreaker` (Resilience4j) | `LOWEST_PRECEDENCE - 4` |
| `@Retry` (Resilience4j) | `LOWEST_PRECEDENCE - 3` |
| `@Transactional` | `LOWEST_PRECEDENCE` |

### AOP Configuration

```yaml
bluetape4k:
  leader:
    aop:
      enabled: true               # default true
      strict: false               # strict = true → startup fail on footgun
      failure-mode: RETHROW       # RETHROW (default) or SKIP
      default-wait-time: PT5S
      default-lease-time: PT1M
      lock-name-prefix: ""        # default: "${spring.application.name}:"
      spel:
        allow-method-invocation: false
```

---

## Backend Quick Start

### 1. Add dependency

```kotlin
dependencies {
    implementation("io.github.bluetape4k.leader:leader-spring-boot3:<version>")
    // Pick one or more backends:
    implementation("io.github.bluetape4k.leader:leader-redis-lettuce:<version>")
    implementation("io.github.bluetape4k.leader:leader-redis-redisson:<version>")
    implementation("io.github.bluetape4k.leader:leader-mongodb:<version>")
    implementation("io.github.bluetape4k.leader:leader-hazelcast:<version>")
    implementation("io.github.bluetape4k.leader:leader-exposed-jdbc:<version>")
    implementation("io.github.bluetape4k.leader:leader-exposed-r2dbc:<version>")
}
```

If no backend module is on the classpath, the **Local (in-memory) fallback** is auto-registered.
Useful for development and tests without external infrastructure.

### 2. Provide a backend client bean

```kotlin
@Configuration
class RedisConfig {
    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient =
        Redisson.create(Config().apply { useSingleServer().setAddress("redis://localhost:6379") })
}
```

### 3. Inject and use

```kotlin
@Service
class CronJobService(private val leader: LeaderElection) {
    @Scheduled(cron = "0 */15 * * * *")
    fun runIfPrimary() {
        leader.runIfLeader("cron-job") {
            // executed on a single node only
        }
    }
}
```

## Bean Registration Matrix

| Backend     | Activation Condition                                | Beans Registered                                                                                                                              |
|-------------|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **Local** ⭐ | No other backend bean is present (fallback)         | `localLeaderElection`, `localSuspendLeaderElection`, `localLeaderGroupElection`, `localSuspendLeaderGroupElection`                            |
| Redisson    | `RedissonClient` bean present                       | `redissonLeaderElection`, `redissonSuspendLeaderElection`, `redissonLeaderGroupElection`, `redissonSuspendLeaderGroupElection`                |
| Lettuce     | `StatefulRedisConnection<String, String>` present   | `lettuceLeaderElection`, `lettuceSuspendLeaderElection`, `lettuceLeaderGroupElection`, `lettuceSuspendLeaderGroupElection`                    |
| Mongo       | `MongoDatabase` and/or `CoroutineMongoDatabase`     | `mongoLeaderElection`, `mongoLeaderGroupElection` (sync); `mongoSuspendLeaderElection`, `mongoSuspendLeaderGroupElection` (coroutine)         |
| Hazelcast   | `HazelcastInstance` present                         | `hazelcastLeaderElection`, `hazelcastSuspendLeaderElection`, `hazelcastLeaderGroupElection`, `hazelcastSuspendLeaderGroupElection`            |
| ExposedJdbc | `org.jetbrains.exposed.v1.jdbc.Database` present    | `exposedJdbcLeaderElection`, `exposedJdbcLeaderGroupElection`, `exposedJdbcVirtualThreadLeaderElection`                                       |
| ExposedR2dbc| `R2dbcDatabase` present                             | `exposedR2dbcSuspendLeaderElection`, `exposedR2dbcSuspendLeaderGroupElection`                                                                 |

⭐ Local beans are registered with `@ConditionalOnMissingBean(<type>::class)` — they back off automatically when any other backend bean of the same type is registered.

## Configuration

YAML prefix: `bluetape4k.leader`.

```yaml
bluetape4k:
  leader:
    wait-time: 5s          # default 5s
    lease-time: 60s        # default 60s
    group:
      max-leaders: 2       # default 2
      wait-time: 5s
      lease-time: 60s
    mongo:
      single-collection: leader_election        # default
      group-collection:  leader_group_election  # default
```

## Multi-Backend Usage

When multiple backends are active simultaneously (e.g. Lettuce + Redisson), every bean has a unique name,
so the container holds them all without conflict. However `@Autowired LeaderElection` becomes ambiguous
and Spring throws `NoUniqueBeanDefinitionException`.

Use `@Qualifier` to disambiguate:

```kotlin
@Service
class MyService(
    @Qualifier("lettuceLeaderElection") private val leader: LeaderElection,
)
```

## Custom Bean Override

Each backend bean uses `@ConditionalOnMissingBean(name = "<beanName>")`. To override, register a bean
with the same name in your own `@Configuration`:

```kotlin
@Bean(name = ["redissonLeaderElection"])
fun customRedissonLeader(client: RedissonClient): LeaderElection =
    RedissonLeaderElection(client, LeaderElectionOptions(waitTime = 10.seconds, leaseTime = 5.minutes))
```

## Notes & Trade-offs

- **`runBlocking` at startup** — Mongo `suspend` beans and ExposedR2dbc `suspend` beans wrap their
  `suspend operator fun invoke()` in `runBlocking` to satisfy Spring's synchronous `@Bean` contract.
  Schema initialization runs once during context startup; no impact on request-time latency.
- **Mongo backend requirements** — `mongoLeaderElection` requires `MongoDatabase` (sync driver),
  `mongoSuspendLeaderElection` requires `CoroutineMongoDatabase` (coroutine driver), and
  `mongoSuspendLeaderGroupElection` requires *both* (sync + coroutine on the same namespace).
- **Backend-specific options** — `retryDelay`, `retryStrategy`, `recordHistory`, `lockOwner` etc. are
  not exposed via properties in v1.0; backend defaults are used. Subsequent issue will surface them.

## Architecture

`LeaderElectionAutoConfiguration` is the entry point (`@AutoConfiguration`), gated by
`@ConditionalOnClass(LeaderElection::class)`. It `@Import`s 7 backend `@Configuration` classes,
each gated by their own `@ConditionalOnClass` + `@ConditionalOnBean`. The `LocalLeaderConfiguration`
is annotated with `@AutoConfigureOrder(LOWEST_PRECEDENCE)` and uses `@ConditionalOnMissingBean(<type>)`
so it activates only when no other backend produces the same type bean.

## See Also

- [leader-spring-boot-common](../leader-spring-boot-common/README.md) — AOP annotations, SpEL guide, startup validation, failure modes
- [Project root README](../README.md)
