# Lessons Learned — leader-spring-boot 커버리지 개선 (2026-05-12)

**영향 모듈**: leader-spring-boot
**커버리지 변화**: 49.95% → 91.7%

---

## L1: suspend 분기 단위 테스트 — `EmptyCoroutineContext` + `withContext` 타임아웃 함정

### 문제
`aroundLeaderSuspend` 를 테스트하기 위해 `Continuation<Any?>(EmptyCoroutineContext) { ... }` 를 직접 생성하고
`CompletableDeferred` 로 결과를 기다렸다. 첫 버전 14개 테스트가 전부 `UncompletedCoroutinesError` 로 실패.

```
After waiting for 1m, the test body did not run to completion
```

### 원인
`Continuation` 의 `coroutineContext` 가 `EmptyCoroutineContext` 이면 `ContinuationInterceptor` 가 없다.
`withContext(LeaderElectionInfo(...))` 가 내부에서 호출될 때, Kotlin 은 interceptor 가 없으면
`Dispatchers.Default` 를 암묵적으로 추가해 실제 스레드 풀로 dispatch 한다.
`runTest` 는 `StandardTestDispatcher` 를 사용하므로 실제 스레드에서 실행된 코루틴을 advance 하지 못해
1분 뒤 타임아웃이 발생한다.

### 해결책
`suspendCancellableCoroutine { cont -> ... }` 를 `runTest` 블록 안에서 사용하면
`runTest` 의 `TestCoroutineScheduler` 를 포함한 진짜 `Continuation` 을 얻는다.
이 `Continuation` 을 `pjp.args.last()` 로 주입하면 `withContext` 가 `TestDispatcher` 위에서 dispatch 된다.

```kotlin
private suspend fun runSuspendAspect(aspect: LeaderElectionAspect): Any? =
    suspendCancellableCoroutine { cont ->
        every { pjp.args } returns arrayOf<Any?>(cont)
        val r = aspect.aroundLeader(pjp)
        @Suppress("SuspiciousEqualsCombination")
        if (r !== COROUTINE_SUSPENDED) {
            cont.resume(r)
        }
    }
```

**How to apply:** suspend AOP를 단위 테스트할 때는 `Continuation` 을 직접 생성하지 말고
`runTest { suspendCancellableCoroutine { cont -> ... } }` 패턴으로 실제 `Continuation` 을 획득해 주입한다.
`r !== COROUTINE_SUSPENDED` 가드로 동기 완료와 실제 정지를 모두 처리한다.

---

## L2: Freefair CTW test sourceSet 제약 — suspend/Mono 분기 직접 호출 패턴

### 문제
`@LeaderElection` 어노테이션이 붙은 test 클래스 메서드가 AOP 인터셉션이 되지 않아
suspend / Mono 분기 코드를 통합 테스트 방식으로 검증할 수 없었다.

### 교훈
Freefair CTW 는 main sourceSet 에만 weaving 적용. test 클래스는 대상이 아니다.
따라서 suspend / Mono 분기 커버리지는:
1. `aspect.aroundLeader(pjp)` 직접 호출
2. `MethodSignature` + `ProceedingJoinPoint` 를 MockK 로 구성
3. Fake elector 구현체 사용 (MockK `coEvery` 대신)

Fake elector 패턴이 `coEvery` 보다 안정적이다 — MockK suspend stub 의 내부 dispatch 이슈 없음:

```kotlin
private class ElectedSuspendElector : SuspendLeaderElector {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = action()
}
private class SkippedSuspendElector : SuspendLeaderElector {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = null
}
private class BackendErrorSuspendElector(private val error: Exception) : SuspendLeaderElector {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? = throw error
}
```

**How to apply:** CTW 모듈에서 suspend AOP 단위 테스트는 항상 이 Fake elector + `suspendCancellableCoroutine` 패턴 조합 사용.
Mono 분기는 `(aspect.aroundLeader(pjp) as Mono<*>).block()` 으로 단순 검증 가능.

---

## L3: 라이브러리 모듈 `@SpringBootTest` — `@SpringBootConfiguration` + `@ImportAutoConfiguration` 패턴

### 문제
`leader-spring-boot` 는 `@SpringBootApplication` 클래스가 없는 라이브러리 모듈이라
`@SpringBootTest` 를 사용하면 `No qualifying bean of type '@SpringBootConfiguration'` 오류 발생.

`@EnableAutoConfiguration` 을 포함한 `@SpringBootApplication` 을 만들면 클래스패스의 모든
AutoConfiguration 이 로딩되어 테스트가 느리고 의도치 않은 빈이 생성된다.

### 해결책
두 가지를 분리한다:

1. **`LeaderTestApplication`** — `@SpringBootConfiguration` 만 포함 (컴포넌트 스캔 없음, AutoConfiguration 없음)

```kotlin
@SpringBootConfiguration
open class LeaderTestApplication
```

2. **각 테스트 클래스** — `@ImportAutoConfiguration` 으로 필요한 AutoConfig 만 명시 로딩

```kotlin
@SpringBootTest(
    classes = [LeaderTestApplication::class, MyTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
class MyTest {
    @TestConfiguration
    open class TestConfig {
        @Bean fun backendBean() = ...
    }
}
```

`ApplicationContextRunner.withConfiguration(AutoConfigurations.of(...))` 의 선언적 등가.
`@ConditionalOnBean` 조건은 `TestConfig` 의 빈으로 충족시킨다.

**How to apply:** 라이브러리 AutoConfiguration 테스트는 `@SpringBootConfiguration` + `@ImportAutoConfiguration` 조합.
`@SpringBootApplication` 또는 `@EnableAutoConfiguration` 은 불필요하고 느리다.

---

## L4: `@ConditionalOnBean` 커버리지 — 백엔드 빈 주입 전략

### 문제
`LeaderAopFactoryAutoConfiguration$LettuceFactoryConfig` 가 20% (1/5) 였다.
클래스 선언 1줄은 커버됐으나 `@Bean` 메서드 4개(4줄)가 미커버.
`@ConditionalOnBean(StatefulRedisConnection::class)` 조건 미충족이 원인.

### 교훈
AutoConfiguration 의 `@ConditionalOnBean` 으로 감싼 `@Bean` 메서드는
해당 조건 빈 없이는 실행되지 않는다 — 클래스가 인스턴스화 돼도 메서드는 스킵.

`ApplicationContextRunner.withUserConfiguration(Config::class.java)` 또는
`@SpringBootTest` 의 `@TestConfiguration` 으로 백엔드 빈을 제공해야 `@Bean` 메서드가 실행된다.

**Hazelcast 예외**: testcontainers 없이 embedded `Hazelcast.newHazelcastInstance(cfg)` 사용 가능.
`networkConfig.join.multicastConfig.isEnabled = false` 설정 필수 (포트 충돌 방지).

```kotlin
@Bean(destroyMethod = "shutdown")
fun hazelcastInstance(): HazelcastInstance {
    val cfg = Config().apply { networkConfig.join.multicastConfig.isEnabled = false }
    return Hazelcast.newHazelcastInstance(cfg)
}
```

**How to apply:** AutoConfiguration inner `@Configuration` 클래스 커버리지 개선 시,
각 `@ConditionalOnBean` 에 맞는 백엔드 빈을 `@TestConfiguration` 으로 제공한다.
`ApplicationContextRunner` 와 `@SpringBootTest` 모두 사용 가능 — 단위성 높으면 runner, 통합이면 `@SpringBootTest`.

---

## L5: Kover 커버리지 측정 범위 — test 실행 대상 포함 여부

### 문제
`./gradlew :leader-spring-boot:koverXmlReport` 가 이전 test 결과를 재활용하지 않고
현재 실행된 테스트만 커버리지에 포함시키는지 확인이 필요했다.

### 교훈
Kover `koverXmlReport` 는 해당 모듈의 **모든 test task 결과**를 집계한다.
`--tests` 필터로 일부만 실행해도 이전 실행 결과(`binary/` 디렉토리)가 누적된다.
전체 커버리지를 정확히 측정하려면 `./gradlew :module:test` (필터 없음) 후 `koverXmlReport` 실행.

**How to apply:** 커버리지 보고 전 `clean` 없이 전체 test 실행 → koverXmlReport 순서 유지.
부분 실행 후 koverXmlReport 는 누적 수치라 신뢰도가 낮다.
