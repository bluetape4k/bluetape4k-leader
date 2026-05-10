# Lessons Learned — examples/ktor-app (2026-05-10)

**관련 PR**: TBD (feat/examples-ktor-app → develop)
**관련 Issue**: #165
**영향 모듈**: `examples/ktor-app/`, `gradle/libs.versions.toml`, `settings.gradle.kts`, `.github/workflows/{ci,nightly}.yml`

## L1: Ktor app + leader-ktor 통합 패턴 — `Application.module(connection)` 파라미터화

### 문제
`KtorAppMain.main()` 은 `embeddedServer(CIO, ...) { module() }` 형태로 자체 Lettuce `RedisClient` + `StatefulRedisConnection` 을 생성하지만, 테스트(`testApplication { ... }`)는 Testcontainers Redis 의 connection 을 주입해야 한다.

### 교훈
모듈 함수를 파라미터화하여 main 진입점과 testApplication 양쪽 재사용. `RedisClient` 가 아닌 `StatefulRedisConnection<String, String>` 을 받도록 한다 — `LettuceSuspendLeaderElector` 의 인자 타입과 일치시켜 불필요한 변환을 피한다:

```kotlin
fun Application.module(
    connection: StatefulRedisConnection<String, String>,
    aggregator: StatsAggregator = StatsAggregator(),
    aggregationLockName: String = KtorAppMain.DEFAULT_AGGREGATION_LOCK,
    aggregationPeriod: Duration = KtorAppMain.DEFAULT_AGGREGATION_PERIOD,
) {
    install(ContentNegotiation) { jackson { ... } }
    install(LeaderElectionPlugin) {
        leaderElection = LettuceSuspendLeaderElector(connection)
    }
    leaderScheduled(aggregationLockName, period = aggregationPeriod) {
        aggregator.aggregate()
    }
    routing { statsRoutes(aggregator) }
}
```

main 은 `RedisClient.create(url) → client.connect(StringCodec.UTF8) → module(connection)` 흐름이며, client + connection 모두 `ShutdownQueue` 에 등록한다 (E1 batch-scheduler 와 동일 패턴). 테스트는 `module(connection = newConnection(), aggregationPeriod = 100.ms)` 호출 — 동일 코드 경로 검증.

---

## L2: Ktor 3.x ContentNegotiation + Jackson + `Instant` 직렬화 — JSR310 모듈 등록 필수

### 문제
`ktor-serialization-jackson` 은 Kotlin 모듈은 자동 등록하지만 `JavaTimeModule` 은 등록하지 않는다. `data class StatsAggregatorState(val lastRunAt: Instant?)` 응답 시 Jackson 이 직렬화 실패하여 **빈 응답 body** 가 반환된다 (status 는 200 OK).

### 증상
```
GET /stats body=
java.lang.IllegalArgumentException: runCount field missing in response: 
```

### 교훈
1. `jackson-datatype-jsr310` 의존성 명시:
   ```kotlin
   implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
   ```
2. ContentNegotiation 설정에서 모듈 + ISO-8601 포맷 지정:
   ```kotlin
   install(ContentNegotiation) {
       jackson {
           registerModule(JavaTimeModule())
           disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
       }
   }
   ```

`/health` 처럼 `Map<String,String>` 만 응답하는 라우트는 정상 동작하므로 디버깅 시 응답 body 의 비어있음 여부와 응답 객체에 `Instant`/`LocalDateTime` 등 Java time 타입 포함 여부를 함께 확인할 것.

---

## L3: testApplication + Awaitility 결합 — `runSuspendIO` 사용, `runTest` 금지

### 교훈
`testApplication { ... }` 은 실제 코루틴 + 실시간 동작 — `runTest` 의 가상 시간과 호환되지 않는다 (leader-ktor lessons L4). Awaitility 도 실시간 polling 이므로 `runSuspendIO` (또는 `runBlocking { ... }: Unit`) 로 감싼다.

```kotlin
@Test
fun `xxx`() = runSuspendIO {
    testApplication {
        application { module(connection = newConnection(), aggregationPeriod = 100.ms) }
        startApplication()

        await.atMost(15.seconds.toJavaDuration())
            .withPollInterval(100.milliseconds.toJavaDuration())
            .until { aggregator.currentState().runCount >= 3L }
    }
}
```

Polling interval 은 `aggregationPeriod` 의 1~2배로 설정하여 race condition 회피.

---

## L4: 다중 인스턴스 시뮬레이션 — 단일 testApplication 안에서 두 leaderScheduled 등록

### 문제
이상적으로는 두 개의 `testApplication` 인스턴스를 동시 실행하여 Redis 락 경합을 시연하지만, `testApplication` 의 라이프사이클은 trailing lambda 종료 시 강제 stop 되어 병렬 실행이 어렵다.

### 교훈
같은 `application { }` 블록 안에서 두 `leaderScheduled(...)` 호출을 등록하면, 별도 elector 가 생성되어 동일 lockName 에 경합한다 — 단일 인스턴스만 cycle 마다 실행되는 동작을 검증할 수 있다. **인스턴스별 별도 `StatefulRedisConnection` 사용** — E1 batch-scheduler 의 `(1..3).map { newConnection() }` 패턴과 동일하게, 각 인스턴스는 자체 connection 을 보유해 락 경합이 실제 분산 환경과 동일하게 동작한다.

```kotlin
val connectionA = newConnection()
val connectionB = newConnection()
testApplication {
    application {
        // 인스턴스 A
        module(connectionA, aggregatorA, sharedLockName, SHORT_PERIOD)
        // 인스턴스 B — 같은 락에 별도 connection + 별도 elector 로 경합
        leaderScheduled(sharedLockName, SHORT_PERIOD,
            leaderElection = LettuceSuspendLeaderElector(connectionB)) {
            aggregatorB.aggregate()
        }
    }
    startApplication()
    // 합 카운터 검증
}
```

각 aggregator 의 `runCount` 합이 양수면 적어도 한쪽이 리더로 동작했음을 검증.

---

## L5: Gradle build cache + Testcontainers — `--no-build-cache` 플래그 강제

### 교훈 (leader-ktor L3 재확인)
Testcontainers Redis 컨테이너 재시작 후 cached 결과로 인해 재실행이 스킵될 수 있다. fresh 검증:

```bash
./gradlew :examples:ktor-app:cleanTest :examples:ktor-app:test --no-daemon --no-build-cache
```

---

## L6: 빈 응답 body 디버깅 — request 시 `Accept: application/json` 명시

### 교훈
Ktor ContentNegotiation 은 클라이언트의 Accept 헤더가 명시되지 않으면 일부 라우트에서 빈 body 를 반환할 수 있다. 테스트 client 로 호출 시 명시적으로:

```kotlin
val response = client.get("/stats") { accept(ContentType.Application.Json) }
```

L2 의 JSR310 누락이 root cause 였지만, accept 헤더 명시 + 응답 body 로깅으로 빠르게 격리 가능.
