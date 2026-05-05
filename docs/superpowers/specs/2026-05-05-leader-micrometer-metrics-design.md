# leader-aop Micrometer Metrics 통합 설계

- **Issue**: #75 `feat: leader-aop Micrometer metrics 통합`
- **작성일**: 2026-05-05
- **대상 모듈**: `leader-micrometer` (신규), `leader-spring-boot3`, `leader-spring-boot4`
- **상태**: Design (구현 전)

---

## 1. Background

### 1.1 문제 정의

현재 `leader-spring-boot-common`의 `LeaderElectionAspect`는 6개의 라이프사이클 콜백을 정의한 `LeaderAopMetricsRecorder` SPI를 통해 관측 지점을 노출하고 있다. 그러나 운영 환경에서 사용 가능한 **기본 구현체(default implementation)** 가 없어, 사용자는 자체적으로 SPI를 구현해야 메트릭을 얻을 수 있다.

본 작업은 가장 보편적인 메트릭 백엔드인 **Micrometer** 기반의 `MicrometerLeaderAopMetricsRecorder`를 제공하여, Spring Boot 3/4 환경에서 별도 구현 없이 Prometheus / Datadog / CloudWatch 등으로 leader-aop 메트릭을 즉시 송출할 수 있도록 한다.

### 1.2 ShedLock 비교

ShedLock의 `MicrometerLockProvider` 패턴을 참고 설계 기준으로 채택하되, leader-aop가 가진 다음 강점을 반영한다.

| 항목 | ShedLock | leader-aop (본 설계) |
|---|---|---|
| 락 획득 시도 / 성공 카운터 | O | O |
| 락 미획득 카운터 | O (단일 카운터) | O + `reason` 태그 (CONTENTION / BACKEND_ERROR) |
| 작업 실행 시간 Timer | O | O |
| **작업 실패 카운터** | X | **O + `exception` 태그** |
| **현재 활성 leader 게이지** | X | **O (`AtomicInteger` 기반)** |
| Spring Boot AutoConfig | X (수동 등록) | **O (Boot3 + Boot4 둘 다)** |
| 락 획득 소요 시간 | X | (옵션, v2 후보) |

### 1.3 비목표 (Non-goals)

- Micrometer **Observation API** / OpenTelemetry tracing 통합 → Issue #85 이후 별도 작업으로 분리
- `LeaderElection` SPI 자체에 대한 메트릭 (락 백엔드 호출 단위 메트릭) → 본 PR은 **AOP 레이어** 만 담당
- async / suspend / reactive 반환 타입 지원 → leader-aop v1.x 범위 외

---

## 2. Architecture

### 2.1 모듈 배치 원칙

`leader-micrometer`는 **Spring Boot 버전에 의존하지 않는다.** Boot3 / Boot4 모두 동일한 `MicrometerLeaderAopMetricsRecorder`를 사용하며, 각 Spring Boot 버전 모듈은 자신의 AutoConfiguration 안에서 동일한 시그니처의 `@Bean` 메서드 하나로 이를 등록한다.

```
leader-core
  └── leader-spring-boot-common  (LeaderAopMetricsRecorder 인터페이스 보유)
        ├── leader-micrometer    (MicrometerLeaderAopMetricsRecorder 구현)
        │     ▲
        │     │ (api dependency)
        │     │
        ├── leader-spring-boot3  (MicrometerAutoConfiguration → @Bean)
        └── leader-spring-boot4  (MicrometerAutoConfiguration → @Bean)
```

### 2.2 의존 관계

`leader-micrometer/build.gradle.kts` 변경 사항:

```kotlin
dependencies {
    api(project(":leader-spring-boot-common"))         // LeaderAopMetricsRecorder 인터페이스
    api(Libs.micrometer_core)                          // MeterRegistry, Counter, Timer, Gauge
    // (테스트) testImplementation(Libs.micrometer_registry_prometheus 또는 SimpleMeterRegistry 포함)
}
```

`leader-spring-boot-common`의 `LeaderAopMetricsRecorder` 인터페이스를 외부로 노출해야 하므로 `api(...)`로 선언한다.

### 2.3 패키지 구조

```
io.bluetape4k.leader.micrometer
  ├── MicrometerLeaderAopMetricsRecorder.kt      // 구현체 (단일 파일)
  ├── MicrometerNames.kt                          // (선택) 메터/태그 이름 상수
  └── package-info / KDoc

io.bluetape4k.leader.spring.boot3.metrics
  └── LeaderMicrometerAutoConfiguration.kt        // Boot3용 AutoConfig

io.bluetape4k.leader.spring.boot4.metrics
  └── LeaderMicrometerAutoConfiguration.kt        // Boot4용 AutoConfig (동일 시그니처)
```

---

## 3. Metrics Design

### 3.1 메터 일람

총 **6개의 메터**를 등록한다. 모든 메터는 공통 prefix `leader.aop.*`를 사용한다.

| Meter name | Type | Tags | 트리거 콜백 | 의미 |
|---|---|---|---|---|
| `leader.aop.attempts` | Counter | `lock.name` | `onLockAttempt` | 락 획득 시도 횟수 |
| `leader.aop.acquired` | Counter | `lock.name` | `onLockAcquired` | 락 획득 성공 (= leader 선출) 횟수 |
| `leader.aop.lock.not.acquired` | Counter | `lock.name`, `reason` | `onLockNotAcquired` | 미획득. `reason ∈ {CONTENTION, BACKEND_ERROR}` |
| `leader.aop.execution.duration` | Timer | `lock.name` | `onTaskFinished` | 정상 종료된 작업의 attempt → completion 경과 시간 |
| `leader.aop.task.failed` | Counter | `lock.name`, `exception` | `onTaskFailed` | 작업 본문에서 던진 예외 발생 횟수. `exception` = `throwable::class.simpleName` |
| `leader.aop.active` | Gauge (`AtomicInteger`) | `lock.name` | `onTaskStarted` (+1) / `onTaskFinished` & `onTaskFailed` (-1) | 동시 실행 중인 leader 작업 수 (JVM-local) |

> **`leader.aop.active`는 JVM-local 값이다.** 멀티 인스턴스 클러스터에서 Prometheus 집계 시 `sum` 대신 `max by (lock_name) (leader_aop_active)`를 사용해야 올바른 값을 얻을 수 있다. `sum`을 사용하면 인스턴스 수 × actual count가 된다.

### 3.2 네이밍 근거

- **Prefix `leader.aop.`** : leader-core 자체 메트릭(향후 #76 등)과 명확히 구분하기 위해 `aop` 세그먼트를 둔다.
- **완전 dot-separated**: Micrometer의 `NamingConvention`이 백엔드별로 자동 변환(Prometheus는 `leader_aop_attempts_total`, Datadog은 `leader.aop.attempts.count` 등)하므로, **모든 세그먼트를 점(.)으로만 구분**한다. underscore 혼용 금지 — `execution_time` 대신 `execution.duration`, `lock_not_acquired` 대신 `lock.not.acquired`.
- **`lock.name`** : ShedLock 관례(`name`)와 차별화하면서, leader-aop의 SpEL 결과 락 이름임을 명확히 한다. dot-prefix는 Micrometer 권장 태그 네이밍.

### 3.3 태그 카디널리티 가이드

- `lock.name`: 사용자가 SpEL로 결정. **동적 SpEL 사용 시 카디널리티 폭발 주의**가 필요. 본 모듈은 정책을 강제하지 않으며, KDoc 및 README에 경고 문구를 명시한다.
- `reason`: enum 2개 (`CONTENTION` / `BACKEND_ERROR`) → 안전.
- `exception`: `simpleName`만 사용 → 일반적으로 수십 개 이내, 안전.

### 3.4 메터 캐싱 전략 (ShedLock 패턴 채택)

콜백마다 `registry.counter(name, tags)`를 호출하면 매 호출 내부 lookup이 발생한다. 따라서 **`ConcurrentHashMap<String, Meter>` 단위 캐싱**을 적용한다.

> ShedLock과 달리 우리는 콜백 시그니처에 `Duration`이 직접 전달되므로 **ThreadLocal Timer.Sample** 패턴은 불필요하다. `timer.record(executionTime)`을 직접 호출한다.

```kotlin
// 타입 별칭 — Pair는 data class이므로 equals/hashCode 정상 동작
private typealias NotAcquiredKey = Pair<String, SkipReason>
private typealias FailedKey      = Pair<String, String>

private val attemptCounters     = ConcurrentHashMap<String, Counter>()
private val acquiredCounters    = ConcurrentHashMap<String, Counter>()
private val notAcquiredCounters = ConcurrentHashMap<NotAcquiredKey, Counter>()
private val executionTimers     = ConcurrentHashMap<String, Timer>()
private val failedCounters      = ConcurrentHashMap<FailedKey, Counter>()
private val activeGauges        = ConcurrentHashMap<String, AtomicInteger>()
```

`computeIfAbsent`로 lazy 등록. `notAcquiredCounters`와 `failedCounters`는 `reason`/`exception` 카디널리티가 미지이므로 `registerMetricsFor`에서 사전 등록하지 않는다.

### 3.5 사전 등록 (Pre-registration)

ShedLock의 `registerMetricsFor(vararg names: String)` 패턴을 채택한다. 첫 호출 전까지 메트릭이 시계열 백엔드에 노출되지 않는 문제를 해결한다.

```kotlin
fun registerMetricsFor(vararg lockNames: String) {
    lockNames.forEach { name ->
        attemptCounters.computeIfAbsent(name) { buildCounter("leader.aop.attempts", name) }
        acquiredCounters.computeIfAbsent(name) { buildCounter("leader.aop.acquired", name) }
        executionTimers.computeIfAbsent(name) { buildTimer(name) }
        activeGauges.computeIfAbsent(name) { buildActiveGauge(name) }
        // notAcquired / failed는 reason / exception 카디널리티가 미지이므로 lazy 유지
    }
}

// Gauge 등록 — 올바른 Micrometer 람다 패턴 (!! 금지, toDouble() 명시)
private fun buildActiveGauge(lockName: String): AtomicInteger {
    val counter = AtomicInteger(0)
    Gauge.builder("leader.aop.active", counter) { it.get().toDouble() }
         .tag("lock.name", lockName)
         .register(registry)
    return counter
}
```

**`registerMetricsFor`는 멱등**해야 한다. `computeIfAbsent`가 이를 보장한다 — 동일 이름으로 두 번 호출해도 미터가 중복 등록되지 않는다.

**SmartInitializingSingleton 권장**: `@PostConstruct`보다 `SmartInitializingSingleton.afterSingletonsInstantiated()` 또는 `ApplicationReadyEvent`에서 호출해야, `CompositeMeterRegistry`의 모든 delegate가 완전히 바인딩된 후 Gauge가 등록된다.

**Gauge 해제 (`deregisterMetricsFor`)**: 동적 SpEL로 lock.name이 변경되거나 잡이 제거된 경우, `ConcurrentHashMap`에 남은 `AtomicInteger`와 `MeterRegistry` 내부 참조가 메모리 누수로 이어진다. 구현체는 `deregisterMetricsFor(vararg lockNames: String)` API도 제공해야 한다.

```kotlin
fun deregisterMetricsFor(vararg lockNames: String) {
    lockNames.forEach { name ->
        attemptCounters.remove(name)?.let { registry.remove(it) }
        acquiredCounters.remove(name)?.let { registry.remove(it) }
        executionTimers.remove(name)?.let { registry.remove(it) }
        activeGauges.remove(name)?.also { gauge ->
            registry.find("leader.aop.active").tag("lock.name", name).gauge()
                ?.let { registry.remove(it) }
        }
    }
}
```

---

## 4. Implementation Design

### 4.1 `MicrometerLeaderAopMetricsRecorder`

```kotlin
package io.bluetape4k.leader.micrometer

import io.bluetape4k.leader.LeaderElectionOptions          // ✅ 올바른 패키지
import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
import io.bluetape4k.leader.spring.aop.metrics.SkipReason
import io.micrometer.core.instrument.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class MicrometerLeaderAopMetricsRecorder(
    private val registry: MeterRegistry,
) : LeaderAopMetricsRecorder {

    // --- caches (3.4 참조) ---
    private val attemptCounters     = ConcurrentHashMap<String, Counter>()
    private val acquiredCounters    = ConcurrentHashMap<String, Counter>()
    private val notAcquiredCounters = ConcurrentHashMap<Pair<String, SkipReason>, Counter>()
    private val executionTimers     = ConcurrentHashMap<String, Timer>()
    private val failedCounters      = ConcurrentHashMap<Pair<String, String>, Counter>()
    private val activeGauges        = ConcurrentHashMap<String, AtomicInteger>()

    // --- callbacks ---
    override fun onLockAttempt(name: String, options: LeaderElectionOptions) {
        attemptCounter(name).increment()
    }

    override fun onLockAcquired(name: String, options: LeaderElectionOptions, acquireElapsed: Duration) {
        acquiredCounter(name).increment()
        // (옵션 v2) acquireTimer(name).record(acquireElapsed.toJavaDuration())
    }

    override fun onLockNotAcquired(name: String, options: LeaderElectionOptions, reason: SkipReason) {
        notAcquiredCounter(name, reason).increment()
    }

    override fun onTaskStarted(name: String) {
        activeGauge(name).incrementAndGet()
    }

    override fun onTaskFinished(name: String, executionTime: Duration) {
        try {
            executionTimer(name).record(executionTime.toJavaDuration())
        } finally {
            activeGauge(name).decrementAndGet()
        }
    }

    override fun onTaskFailed(name: String, executionTime: Duration, throwable: Throwable) {
        try {
            failedCounter(name, throwable::class.simpleName ?: "Unknown").increment()
        } finally {
            // ⚠️ onTaskFailed는 onTaskStarted 없이도 호출될 수 있다 (backend error path).
            // decrementAndGet() 전에 현재 값이 0보다 큰지 확인해 음수 방지.
            activeGauge(name).updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    // NOTE: onTaskFailed에서 executionTime을 execution.duration 타이머에 기록하지 않는다 (의도적).
    // 실패한 실행의 소요 시간은 task.failed counter의 exception 태그로 분류한다.
    // 실패 latency 분석이 필요한 경우 v2에서 outcome 태그 추가를 검토한다.

    // --- pre-registration ---
    fun registerMetricsFor(vararg lockNames: String) { /* 3.5 참조 */ }

    // --- private helpers (computeIfAbsent + Counter/Timer/Gauge 등록) ---
}
```

### 4.2 격리(Isolation) 보장

`LeaderElectionAspect`는 metrics recorder의 throw가 본 작업 흐름에 영향을 주지 않도록 **try/catch로 감싸서 호출**해야 한다. (이는 기존 aspect 동작이 이미 그러하다고 가정하나, 본 spec 검토 시 확인하고 필요 시 보강한다.)

본 구현체 자체는 **throw를 발생시키지 않는다** — `Micrometer`의 `Counter.increment()` 등이 internal exception을 던질 가능성은 무시 가능 수준.

### 4.3 Spring Boot AutoConfiguration

#### 4.3.1 Boot3 / Boot4 공통 시그니처

```kotlin
@AutoConfiguration
@ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
@ConditionalOnProperty(prefix = "bluetape4k.leader.aop.metrics", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class LeaderMicrometerAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry::class)
    @ConditionalOnMissingBean(MicrometerLeaderAopMetricsRecorder::class)
    fun micrometerLeaderAopMetricsRecorder(
        registry: MeterRegistry,
    ): LeaderAopMetricsRecorder = MicrometerLeaderAopMetricsRecorder(registry)
}
```

> 메모리 규칙(`feedback_conditional_on_property_all_phases.md`)에 따라 `@ConditionalOnProperty`는 모든 Phase에 적용한다.

#### 4.3.2 AutoConfig 등록 순서

`LeaderAopAutoConfiguration`보다 **먼저** 등록되어야, Aspect가 recorder 빈을 `ObjectProvider`로 주입받을 수 있다. `LeaderAopFactoryAutoConfiguration`과는 의존 관계 없으므로 Factory 이후에 놓아도 된다.

올바른 순서:
```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  ...
  io.bluetape4k.leader.spring.boot3.aop.LeaderAopFactoryAutoConfiguration
  io.bluetape4k.leader.spring.boot3.metrics.LeaderMicrometerAutoConfiguration   ← Factory 이후, AOP 이전
  io.bluetape4k.leader.spring.boot3.aop.LeaderAopAutoConfiguration
```

`@AutoConfigureBefore(LeaderAopAutoConfiguration::class)` + `@AutoConfigureAfter(LeaderAopFactoryAutoConfiguration::class)` 함께 명시(이중 안전).

> **이전 spec 오류 수정**: Micrometer 설정을 Factory보다 앞에 놓을 이유가 없다. Recorder 빈은 Factory 빈에 의존하지 않으므로, 올바른 제약은 "AOP보다 앞" 뿐이다.

#### 4.3.3 Boot3 vs Boot4 차이

코드 레벨로는 **identical**. 패키지만 `boot3` ↔ `boot4`로 다름. 의존하는 Spring Boot 버전 차이로 인해 컴파일 시 분리가 필요할 뿐이다.

### 4.4 HealthIndicator 연동 (Boot 3 only, 본 PR 범위)

> ⛔ **`leader-spring-boot-common`의 `LeaderAopHealthIndicator`는 수정하지 않는다.**  
> `leader-spring-boot-common`에 `MeterRegistry` 의존을 추가하면 Micrometer가 없는 환경에서 `ClassNotFoundException`이 발생하고, 모듈 경계(`leader-spring-boot-common`은 SPI만 포함)를 위반한다.

대신, **`leader-spring-boot3`** 내에서 `MicrometerLeaderAopMetricsRecorder` 빈이 존재할 때 `LeaderAopHealthIndicator`를 교체 혹은 보완하는 별도 `HealthContributor` 빈을 등록한다.

```kotlin
// leader-spring-boot3/.../metrics/LeaderMicrometerAutoConfiguration.kt 내부 추가 @Bean
@Bean
@ConditionalOnBean(MeterRegistry::class, MicrometerLeaderAopMetricsRecorder::class)
@ConditionalOnClass(name = ["org.springframework.boot.actuate.health.HealthContributor"])
fun leaderMicrometerHealthContributor(registry: MeterRegistry): HealthIndicator {
    return HealthIndicator {
        val counters = registry.find("leader.aop.attempts").counters()
        val attempts = counters.sumOf { it.count() }
        Health.up()
            .withDetail("attempts.total", attempts)
            .withDetail("metrics.registered", counters.isNotEmpty())
            .build()
    }
}
```

`counters.isEmpty()` (아직 attempts 없음) 상태도 `UP`으로 반환하되 `metrics.registered = false`로 명확히 구분한다.

Boot4 HealthIndicator 연동은 본 PR 범위 외.

---

## 5. Out of Scope

| 항목 | 처리 |
|---|---|
| Micrometer Observation API / Tracing span | Issue #85 이후 별도 PR |
| `leader.aop.acquire_time` Timer (acquireElapsed) | v2 후보로 KDoc에만 표시 |
| Boot4 HealthIndicator 메트릭 노출 | 본 PR 범위 외 |
| async / suspend / reactive 반환 메트릭 | leader-aop v2 |
| Tag 카디널리티 자동 보호(allow-list) | v2 후보 |

---

## 6. Test Plan

### 6.1 단위 테스트 (`leader-micrometer/src/test`)

`SimpleMeterRegistry`를 사용하여 의존성 없이 검증.

```kotlin
class MicrometerLeaderAopMetricsRecorderTest {
    private lateinit var registry: SimpleMeterRegistry
    private lateinit var recorder: MicrometerLeaderAopMetricsRecorder

    @BeforeEach fun setup() { ... }

    // ✅ 모든 assertion은 tag("lock.name", "test-lock")을 명시적으로 포함해야 함
    // 예: registry.get("leader.aop.attempts").tag("lock.name", "test-lock").counter().count()

    @Test fun `onLockAttempt - attempts counter with lock name tag increments`() { ... }
    @Test fun `onLockAcquired - acquired counter with lock name tag increments`() { ... }
    @Test fun `onLockNotAcquired CONTENTION - reason tag equals CONTENTION`() { ... }
    @Test fun `onLockNotAcquired BACKEND_ERROR - reason tag equals BACKEND_ERROR`() { ... }
    @Test fun `onTaskFinished - execution duration timer count increments`() { ... }
    @Test fun `onTaskFailed - task failed counter with exception tag`() { ... }
    @Test fun `onTaskFailed without prior onTaskStarted - active gauge stays non-negative`() { ... }  // backend error path
    @Test fun `onTaskStarted - active gauge becomes 1`() { ... }   // 중간 상태 검증
    @Test fun `onTaskStarted then onTaskFinished - active gauge returns to 0`() { ... }
    @Test fun `onTaskStarted then onTaskFailed - active gauge returns to 0`() { ... }
    @Test fun `registerMetricsFor - meters appear before first callback`() { ... }
    @Test fun `registerMetricsFor - idempotent second call does not duplicate meters`() { ... }  // ✅ mandatory
    @Test fun `concurrent onTaskStarted and onTaskFinished - active gauge thread safe`() { ... }  // ✅ mandatory (not optional)
    @Test fun `deregisterMetricsFor - removes meters from registry`() { ... }
    // MeterRegistry 미존재 → recorder 빈 미등록 → Boot3/Boot4 AutoConfig 테스트에서 검증
}
```

### 6.2 Boot3 통합 테스트 (`leader-spring-boot3`)

```kotlin
@SpringBootTest(classes = [TestApp::class])
class LeaderMicrometerAutoConfigurationBoot3Test {
    @Test fun `MeterRegistry 빈 존재 시 MicrometerLeaderAopMetricsRecorder 자동 등록`() { ... }
    @Test fun `MeterRegistry 빈 없을 때 recorder 빈 미등록`() { ... }   // ✅ @ConditionalOnBean 검증
    @Test fun `enabled=false 시 빈 미등록`() { ... }
    @Test fun `사용자 정의 LeaderAopMetricsRecorder가 우선`() { ... }   // @ConditionalOnMissingBean 검증
    // E2E: 전체 happy-path 검증 — attempts, acquired, execution.duration, active 모두 assert
    @Test fun `LeaderElectionAspect 통과 시 attempts+acquired+timer 전체 검증`() { ... }
    // E2E: backend error path
    @Test fun `backend 예외 시 lock.not.acquired reason=BACKEND_ERROR 증가`() { ... }
}
```

### 6.3 Boot4 통합 테스트 (`leader-spring-boot4`)

Boot3과 동일 시나리오. 패키지/AutoConfig 클래스만 다름.

### 6.4 커버리지 목표

- `leader-micrometer`: **80%** 이상 (단순 모듈, 분기 적음)
- `leader-spring-boot3/4`의 `LeaderMicrometerAutoConfiguration`: 메모리 규칙 (`feedback_kover_unit_test_only_threshold.md`)에 따라 통합 모듈 기준 **60%**

---

## 7. DoD Checklist

이슈 #75 DoD 중 본 PR에 포함되는 항목:

- [ ] `leader-micrometer/build.gradle.kts`에서 기존 `api(project(":leader-core"))` → `api(project(":leader-spring-boot-common"))`로 **교체** (`leader-core`는 `leader-spring-boot-common`의 transitive dep으로 충분)
- [ ] `MicrometerLeaderAopMetricsRecorder` 구현 (6 콜백 + `registerMetricsFor` + `deregisterMetricsFor`)
- [ ] `ConcurrentHashMap` 기반 메터 캐싱 적용
- [ ] `leader-spring-boot3`에 `LeaderMicrometerAutoConfiguration` + `AutoConfiguration.imports` 등록
- [ ] `leader-spring-boot4`에 동일 등록
- [ ] AutoConfig 순서: `LeaderAopFactoryAutoConfiguration` → `LeaderMicrometerAutoConfiguration` → `LeaderAopAutoConfiguration`
- [ ] `@ConditionalOnProperty(... matchIfMissing = true)` (기본 활성)
- [ ] 단위 테스트 (SimpleMeterRegistry 기반) — 6 콜백 + 사전 등록 + isolation
- [ ] Boot3 / Boot4 통합 테스트 — AutoConfig + ConditionalOnMissingBean + E2E
- [ ] Kover 커버리지 통과 (`leader-micrometer` 80%, AutoConfig 60%)
- [ ] `leader-micrometer/README.md` + `README.ko.md` 작성 (메터 표 + 사용 예시 + 카디널리티 경고)
- [ ] 모든 public API에 KDoc
- [ ] 6중 코드 리뷰 (CRITICAL/HIGH 0)

**제외(Deferred):**
- [ ] ~~Micrometer Observation API / Tracing span~~ → #85 이후
- [ ] ~~`leader.aop.acquire_time` Timer~~ → v2

---

## 8. Files to Create / Modify

### 8.1 생성 (Create)

| 경로 | 내용 |
|---|---|
| `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt` | 핵심 구현체 |
| `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt` | (선택) 메터/태그 이름 상수 |
| `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorderTest.kt` | 단위 테스트 |
| `leader-micrometer/src/test/resources/junit-platform.properties` | PER_CLASS + parallel=false (메모리 규칙) |
| `leader-micrometer/README.md` | 영문 사용 가이드 |
| `leader-micrometer/README.ko.md` | 한글 사용 가이드 |
| `leader-spring-boot3/src/main/kotlin/io/bluetape4k/leader/spring/boot3/metrics/LeaderMicrometerAutoConfiguration.kt` | Boot3 AutoConfig |
| `leader-spring-boot4/src/main/kotlin/io/bluetape4k/leader/spring/boot4/metrics/LeaderMicrometerAutoConfiguration.kt` | Boot4 AutoConfig (시그니처 동일) |
| `leader-spring-boot3/src/test/kotlin/.../LeaderMicrometerAutoConfigurationBoot3Test.kt` | Boot3 통합 테스트 |
| `leader-spring-boot4/src/test/kotlin/.../LeaderMicrometerAutoConfigurationBoot4Test.kt` | Boot4 통합 테스트 |

### 8.2 수정 (Modify)

| 경로 | 변경 |
|---|---|
| `leader-micrometer/build.gradle.kts` | `api(project(":leader-core"))` → `api(project(":leader-spring-boot-common"))`로 **교체** |
| `leader-spring-boot3/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | `LeaderMicrometerAutoConfiguration`을 `LeaderAopFactoryAutoConfiguration` 뒤, `LeaderAopAutoConfiguration` 앞에 추가 |
| `leader-spring-boot4/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 동일 |
| `leader-bom/build.gradle.kts` | **변경 불필요** — `leader-micrometer`는 이미 BOM에 등록되어 있음 (`api(project(":leader-micrometer"))` 확인됨) |

### 8.3 검증 (Verify only — 변경 없을 가능성)

- `leader-spring-boot-common/.../LeaderAopMetricsRecorder.kt` — 인터페이스가 본 spec의 시그니처와 일치하는지 (특히 `onLockNotAcquired`의 `SkipReason` 파라미터 존재 여부)
- `leader-spring-boot-common/.../LeaderElectionAspect.kt` — recorder 호출이 try/catch로 격리되어 있는지

---

## 9. Risks & Mitigations

| Risk | 영향 | 완화책 |
|---|---|---|
| `lock.name` 카디널리티 폭발 (동적 SpEL 남용) | TSDB 메모리 / 비용 폭증 | README 경고 + KDoc 명시 + 신규 lock.name 등록 시 `log.warn` 출력. v2에서 allow-list 검토 |
| `exception` 태그 카디널리티 (anonymous class 등) | 동일 | `simpleName` 사용. `null` → `"Unknown"`로 정규화 |
| Gauge 메모리 누수 (동적 lock.name 미해제) | 메모리 누수 / TSDB 비용 | `deregisterMetricsFor` API 제공. KDoc 및 README에 동적 SpEL 사용 시 반드시 호출하도록 명시 |
| `leader.aop.active` JVM-local → multi-instance 오해 | 잘못된 대시보드 집계 | KDoc + README에 `max by (lock_name)` 권장 PromQL 예시 명시 |
| Micrometer 미존재 환경에서 ClassLoading 실패 | AutoConfig 실패 | `@ConditionalOnClass(name = [...])`로 보호 |
| 사용자 정의 recorder 무시 | 기능 손상 | `@ConditionalOnMissingBean`으로 우선권 보장 |
| `onTaskFailed` 음수 active gauge | 게이지 영구 음수 | `updateAndGet { if (it > 0) it - 1 else 0 }` 패턴 적용 |
| Boot3/Boot4 AutoConfig 코드 중복 | 유지보수 부담 | 시그니처 동일 → 변경 시 두 파일 동시 패치 |

---

## 10. Future Work (post-#75)

1. **#85 Observation API 통합** — `MicrometerObservationLeaderAopMetricsRecorder` 추가, OpenTelemetry trace 연계
2. `leader.aop.acquire_time` Timer 추가 (`acquireElapsed` 활용)
3. Tag allow-list / cardinality limiter 옵션
4. Boot4 HealthIndicator 메트릭 노출
5. `leader-core` SPI 자체에 대한 백엔드 단위 메트릭 (Redis 호출 latency 등)
