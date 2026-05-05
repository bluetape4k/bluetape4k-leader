# Implementation Plan — leader-aop Micrometer Metrics (#75)

- **Issue**: #75 `feat: leader-aop Micrometer metrics 통합`
- **작성일**: 2026-05-05
- **대상 모듈**: `leader-micrometer` (구현체), `leader-spring-boot3`, `leader-spring-boot4` (AutoConfig)
- **Spec 참조**: `docs/superpowers/specs/2026-05-05-leader-micrometer-metrics-design.md`

---

## 0. 요약

본 PR은 다음 산출물을 생성한다.

1. `MicrometerLeaderAopMetricsRecorder` — 6개 콜백을 6개 Micrometer 미터에 기록하는 단일 SPI 구현체
2. `MicrometerNames` — 메터/태그 이름 상수
3. `LeaderMicrometerAutoConfiguration` × 2 (Boot3 / Boot4) — `MeterRegistry` 존재 시 recorder 빈을 자동 등록 (`HealthContributor`는 Boot3 only)
4. 단위 테스트 + Boot3/Boot4 통합 테스트 (Kover 80% / 60%)
5. `README.md` + `README.ko.md` (메터 표 + 카디널리티 경고)

**확정된 아키텍처 결정** (Spec 검토 + advisor 합의):

- `leader-micrometer/build.gradle.kts`: `api(project(":leader-core"))` → `api(project(":leader-spring-boot-common"))` **교체**
- 메터 이름: `leader.aop.attempts`, `leader.aop.acquired`, `leader.aop.lock.not.acquired`, `leader.aop.execution.duration`, `leader.aop.task.failed`, `leader.aop.active`
- `onTaskFailed` active gauge 감소: `updateAndGet { if (it > 0) it - 1 else 0 }` (음수 방지)
- AutoConfig 순서: `LeaderAopFactoryAutoConfiguration` → `LeaderMicrometerAutoConfiguration` → `LeaderAopAutoConfiguration`
- `leader-spring-boot-common/LeaderAopHealthIndicator.kt` **수정 금지** (모듈 경계 보존)
- 신규 `HealthContributor` 빈은 `leader-spring-boot3/.../metrics/LeaderMicrometerAutoConfiguration.kt` 안에서 등록
- `deregisterMetricsFor` API 필수 (Gauge 메모리 누수 예방)
- Gauge 등록은 `Gauge.builder("leader.aop.active", counter) { it.get().toDouble() }` 람다 — `!!` 금지
- `typealias NotAcquiredKey = Pair<String, SkipReason>`, `typealias FailedKey = Pair<String, String>`
- 신규 `lock.name`이 처음 등록될 때 `log.warn` 출력 (카디널리티 경고)

---

## Task List

### T1. [complexity: low] build.gradle.kts 의존성 교체 + Kover threshold

**파일**: `leader-micrometer/build.gradle.kts`

**작업**:
- `api(project(":leader-core"))` → `api(project(":leader-spring-boot-common"))` 로 라인 교체
  (`leader-core`는 `leader-spring-boot-common`이 transitive로 노출하므로 별도 명시 불필요)
- `api(libs.micrometer.core)` 유지
- `testImplementation(libs.micrometer.registry.prometheus)` 유지
- `testImplementation(libs.bluetape4k.junit5)` / `kotlinx.coroutines.test` 유지
- **Kover 80% threshold 추가** (`feedback_coverage_kover.md`):
  ```kotlin
  kover {
      reports {
          verify {
              rule {
                  minBound(80)
              }
          }
      }
  }
  ```
  기존 다른 모듈 (`leader-core`, `leader-redis-lettuce` 등)의 Kover 설정 블록 참조하여 동일 패턴 적용.

**검증**: `./gradlew :leader-micrometer:dependencies | grep "leader-spring-boot-common"`

**의존**: 없음 (가장 먼저 수행 — 후속 task의 import 기반)

---

### T2. [complexity: low] MicrometerNames.kt 상수 파일 생성

**파일**: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt`

**작업**: `internal object MicrometerNames` 안에 다음 상수 정의 (KDoc 포함).

```kotlin
internal object MicrometerNames {
    const val METER_ATTEMPTS = "leader.aop.attempts"
    const val METER_ACQUIRED = "leader.aop.acquired"
    const val METER_NOT_ACQUIRED = "leader.aop.lock.not.acquired"
    const val METER_EXECUTION_DURATION = "leader.aop.execution.duration"
    const val METER_TASK_FAILED = "leader.aop.task.failed"
    const val METER_ACTIVE = "leader.aop.active"

    const val TAG_LOCK_NAME = "lock.name"
    const val TAG_REASON = "reason"
    const val TAG_EXCEPTION = "exception"

    const val UNKNOWN_EXCEPTION = "Unknown"
}
```

**의존**: 없음

---

### T3. [complexity: high] MicrometerLeaderAopMetricsRecorder 구현

**파일**: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt`

**작업**: Spec §4.1 기준 단일 클래스. 핵심 구성 요소:

0. **명시적 import 블록** (파일 상단 — IDE 자동 완성 오류 방지)
   ```kotlin
   import io.bluetape4k.leader.LeaderElectionOptions          // ✅ NOT io.bluetape4k.leader.options.*
   import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder
   import io.bluetape4k.leader.spring.aop.metrics.SkipReason
   import kotlin.time.toJavaDuration
   ```

1. **클래스 시그니처**
   ```kotlin
   class MicrometerLeaderAopMetricsRecorder(
       private val registry: MeterRegistry,
   ) : LeaderAopMetricsRecorder
   ```

2. **typealias 정의** (파일 상단, top-level private)
   ```kotlin
   private typealias NotAcquiredKey = Pair<String, SkipReason>
   private typealias FailedKey = Pair<String, String>
   ```

3. **6개 캐시 필드** (`ConcurrentHashMap` 6개)
   - `attemptCounters: ConcurrentHashMap<String, Counter>`
   - `acquiredCounters: ConcurrentHashMap<String, Counter>`
   - `notAcquiredCounters: ConcurrentHashMap<NotAcquiredKey, Counter>`
   - `executionTimers: ConcurrentHashMap<String, Timer>`
   - `failedCounters: ConcurrentHashMap<FailedKey, Counter>`
   - `activeGauges: ConcurrentHashMap<String, AtomicInteger>`

4. **6개 콜백 구현** — 모두 try/finally 패턴 (recorder는 throw 금지)
   - `onLockAttempt(name, options)` → `attemptCounter(name).increment()` + 신규 lockName 등록 시 `log.warn` (카디널리티 경고)
   - `onLockAcquired(name, options, acquireElapsed)` → `acquiredCounter(name).increment()` (acquireElapsed는 v2 보류)
   - `onLockNotAcquired(name, options, reason)` → `notAcquiredCounter(name, reason).increment()`
   - `onTaskStarted(name)` → `activeGauge(name).incrementAndGet()`
   - `onTaskFinished(name, executionTime)` → try { `executionTimer(name).record(executionTime.toJavaDuration())` } finally { `activeGauge(name).decrementAndGet()` }
   - `onTaskFailed(name, executionTime, throwable)` → try { `failedCounter(name, throwable::class.simpleName ?: "Unknown").increment()` } finally { `activeGauge(name).updateAndGet { if (it > 0) it - 1 else 0 }` } **(음수 방지)**

5. **public API**
   - `fun registerMetricsFor(vararg lockNames: String)` — 멱등. attempt/acquired/timer/active 4종을 사전 등록 (notAcquired/failed는 reason/exception 카디널리티 미지로 lazy 유지)
   - `fun deregisterMetricsFor(vararg lockNames: String)` — `ConcurrentHashMap.remove` + `registry.remove(meter)` (Gauge는 `registry.find(...).tag(TAG_LOCK_NAME, name).gauge()` 로 조회 후 제거)

6. **private helpers** (computeIfAbsent + Counter/Timer/Gauge 빌더)
   - `attemptCounter(name)`, `acquiredCounter(name)`, `notAcquiredCounter(name, reason)`, `executionTimer(name)`, `failedCounter(name, exceptionName)`, `activeGauge(name)`
   - `buildActiveGauge(lockName: String): AtomicInteger` — Spec §3.5 람다 패턴 (`{ it.get().toDouble() }`) 사용. **`!!` 금지**.
   - `Gauge.builder(METER_ACTIVE, counter) { it.get().toDouble() }.tag(TAG_LOCK_NAME, lockName).register(registry)`

7. **카디널리티 경고 로깅** — `attemptCounter(name)` 안에서 `computeIfAbsent`의 람다가 호출될 때만 (= 신규 lockName 첫 등장) `log.warn("Registering new lock.name='{}' for leader.aop metrics — beware tag cardinality if using dynamic SpEL", name)` 출력. KLogging companion 사용.
   > ⚠️ **`registerMetricsFor`는 cardinality warn을 발생시키지 않는다.** `registerMetricsFor`는 `attemptCounter(name)` 헬퍼를 통하지 않고 `attemptCounters.computeIfAbsent(name) { buildAttemptCounter(name) }` 를 직접 호출해야 한다. 이렇게 해야 사전 등록된 정적 lock name이 warn을 유발하지 않고, 런타임에 예상치 못한 동적 lock name만 warn을 유발한다.

8. **격리(Isolation) 보장** — 본 클래스 자체에서 throw 금지. `Counter.increment()` 등 Micrometer API 외부 throw는 무시 가능 수준이므로 별도 try/catch 미적용. 단, `log.warn`이 매번 호출되지 않도록 `computeIfAbsent` 람다 안에서만 호출.

**KDoc**: 클래스/모든 public 함수에 KDoc. 클래스 KDoc에는 카디널리티 경고 + multi-instance Prometheus 집계 시 `max by (lock_name) (leader_aop_active)` 사용 권장 명시.

**의존**: T1 (의존성), T2 (상수)

---

### T4. [complexity: medium] Boot3 LeaderMicrometerAutoConfiguration

**파일**: `leader-spring-boot3/src/main/kotlin/io/bluetape4k/leader/spring/boot3/metrics/LeaderMicrometerAutoConfiguration.kt`

**작업**:

1. **AutoConfig 본체**
   ```kotlin
   @AutoConfiguration(
       after = [LeaderAopFactoryAutoConfiguration::class],
       before = [LeaderAopAutoConfiguration::class],
   )
   @ConditionalOnClass(name = ["io.micrometer.core.instrument.MeterRegistry"])
   @ConditionalOnProperty(
       prefix = "bluetape4k.leader.aop.metrics",
       name = ["enabled"],
       havingValue = "true",
       matchIfMissing = true,
   )
   class LeaderMicrometerAutoConfiguration
   ```

2. **Recorder @Bean**
   ```kotlin
   @Bean
   @ConditionalOnBean(MeterRegistry::class)
   @ConditionalOnMissingBean(MicrometerLeaderAopMetricsRecorder::class)
   @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
   fun micrometerLeaderAopMetricsRecorder(
       registry: MeterRegistry,
   ): LeaderAopMetricsRecorder = MicrometerLeaderAopMetricsRecorder(registry)
   ```

3. **HealthContributor @Bean** (Boot3 only — Spec §4.4)
   Spring Boot의 동일 `@Configuration` 클래스 내 `@ConditionalOnBean`은 빈 순서 미보장으로 신뢰 불가. **별도 inner `@Configuration` 클래스**로 분리:
   ```kotlin
   @Configuration(proxyBeanMethods = false)
   @ConditionalOnBean(MicrometerLeaderAopMetricsRecorder::class)
   @ConditionalOnClass(name = ["org.springframework.boot.actuate.health.HealthContributor"])
   inner class HealthConfig {
       @Bean
       @ConditionalOnMissingBean(name = ["leaderMicrometerHealthContributor"])
       fun leaderMicrometerHealthContributor(registry: MeterRegistry): HealthIndicator { ... }
   }
   ```
   - 반환 타입은 `HealthIndicator` (단일 노드). `CompositeHealthContributor`가 아님.
   - `leaderMicrometerHealthContributor` 빈 이름 명시 (`leaderAopHealthIndicator`와 충돌 방지)
   - `attempts.total = registry.find(METER_ATTEMPTS).counters().sumOf { it.count() }`
   - `metrics.registered = counters.isNotEmpty()`
   - 항상 `Health.up()` 반환 (attempts 0 일 때도)
   - `leaderMicrometerHealthContributor`라는 빈 이름 명시 (`leaderAopHealthIndicator`와 충돌 방지)

4. **격리 검증** — `LeaderAopHealthIndicator`(common 모듈)는 그대로 유지. 본 빈은 별개 health 노드로 추가만 한다.

**의존**: T3

---

### T5. [complexity: medium] Boot4 LeaderMicrometerAutoConfiguration

**파일**: `leader-spring-boot4/src/main/kotlin/io/bluetape4k/leader/spring/boot4/metrics/LeaderMicrometerAutoConfiguration.kt`

**작업**:

1. T4와 **시그니처 동일**한 `MicrometerLeaderAopMetricsRecorder` 등록 빈만 작성.
2. `HealthContributor` 빈은 **추가하지 않음** (Spec §4.4 — Boot4 health 통합은 본 PR 범위 외, `org.springframework.boot.health.contributor.HealthIndicator` 신규 패키지 미지원).
3. `@AutoConfiguration(after = [LeaderAopFactoryAutoConfiguration::class], before = [LeaderAopAutoConfiguration::class])` — Boot4의 factory/aop autoconfig 클래스 참조.
4. `@ConditionalOnProperty` 동일.

**의존**: T3

---

### T6. [complexity: medium] AutoConfiguration.imports 순서 등록

**파일** (둘 다 동일하게 수정):
- `leader-spring-boot3/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `leader-spring-boot4/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**작업**: 다음 순서로 라인 삽입.

```
io.bluetape4k.leader.spring.boot3.LeaderElectionAutoConfiguration
io.bluetape4k.leader.spring.boot3.backend.LocalLeaderConfiguration
io.bluetape4k.leader.spring.boot3.aop.autoconfigure.LeaderAopFactoryAutoConfiguration
io.bluetape4k.leader.spring.boot3.metrics.LeaderMicrometerAutoConfiguration   ← 추가 (Factory 뒤, AOP 앞)
io.bluetape4k.leader.spring.boot3.aop.autoconfigure.LeaderAopAutoConfiguration
```

**메모리 규칙 적용**: `feedback_autoconfig_order_separate.md` — `@AutoConfigureBefore/After`만으로는 imports 순서 미보장. 본 파일에서 직접 순서 명시 필수.

Boot4 imports도 동일 패턴 (`boot4` 패키지로).

**의존**: T4, T5

---

### T7. [complexity: medium] 단위 테스트 — MicrometerLeaderAopMetricsRecorderTest

**파일**: `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorderTest.kt`

**작업**: Spec §6.1 기준 14개 테스트 케이스. `SimpleMeterRegistry` 사용 (Testcontainers 불필요).

테스트 클래스 헤더:
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` (메모리 규칙 `feedback_junit_platform_per_class.md`)
- `KLogging companion`
- JUnit 5 + Kluent matcher (`shouldBeEqualTo`, `shouldBeGreaterOrEqualTo`)

테스트 케이스 (모두 backtick-quoted Korean/English):

1. `onLockAttempt - attempts counter with lock name tag increments`
2. `onLockAcquired - acquired counter with lock name tag increments`
3. `onLockNotAcquired CONTENTION - reason tag equals CONTENTION`
4. `onLockNotAcquired BACKEND_ERROR - reason tag equals BACKEND_ERROR`
5. `onTaskFinished - execution duration timer count increments`
6. `onTaskFailed - task failed counter with exception tag`
7. `onTaskFailed without prior onTaskStarted - active gauge stays non-negative` ← **음수 방지 핵심 검증**
8. `onTaskStarted - active gauge becomes 1`
9. `onTaskStarted then onTaskFinished - active gauge returns to 0`
10. `onTaskStarted then onTaskFailed - active gauge returns to 0`
11. `registerMetricsFor - meters appear before first callback`
12. `registerMetricsFor - idempotent second call does not duplicate meters` ← **멱등성 검증 (mandatory)**
13. `concurrent onTaskStarted and onTaskFinished - active gauge thread safe` ← **mandatory**: JVM 실제 스레드 병렬성 사용. `runBlocking(Dispatchers.Default) { coroutineScope { repeat(1000) { launch { recorder.onTaskStarted("k"); recorder.onTaskFinished("k", 1.milliseconds) } } } }` 후 active = 0 검증. `Dispatchers.Default` 명시 필수 (단일 스레드 디스패처 사용 시 AtomicInteger의 thread-safety가 검증되지 않음)
14. `deregisterMetricsFor - removes meters from registry` ← `registry.find(METER_ACTIVE).tag(TAG_LOCK_NAME, name).gauge()` 가 null 반환

모든 assertion은 `tag(TAG_LOCK_NAME, "test-lock")` 명시.

**의존**: T3

---

### T8. [complexity: medium] Boot3 통합 테스트

**파일**: `leader-spring-boot3/src/test/kotlin/io/bluetape4k/leader/spring/boot3/metrics/LeaderMicrometerAutoConfigurationBoot3Test.kt`

**작업**: `@SpringBootTest(classes = [TestApp::class])` 기반.

테스트 케이스 (Spec §6.2):

1. `MeterRegistry 빈 존재 시 MicrometerLeaderAopMetricsRecorder 자동 등록` — `SimpleMeterRegistry` 빈 등록 후 context에서 `MicrometerLeaderAopMetricsRecorder` 빈 조회 성공
2. `MeterRegistry 빈 없을 때 recorder 빈 미등록` — `@ConditionalOnBean(MeterRegistry)` 검증. context에서 `getBeansOfType(LeaderAopMetricsRecorder).isEmpty()` 검증
3. `enabled=false 시 빈 미등록` — `@TestPropertySource(properties = ["bluetape4k.leader.aop.metrics.enabled=false"])`
4. `사용자 정의 LeaderAopMetricsRecorder가 우선` — TestConfig 에서 `@Bean fun customRecorder(): LeaderAopMetricsRecorder = NoOpRecorder()` 등록 후 `MicrometerLeaderAopMetricsRecorder` 빈 미등록 검증
5. `LeaderElectionAspect 통과 시 attempts+acquired+timer+active 전체 검증` — `LocalLeaderElection` + `@LeaderElection` 메서드 호출 → 다음을 **모두** 검증:
   - `registry.get(METER_ATTEMPTS).tag(TAG_LOCK_NAME, "test-lock").counter().count()` ≥ 1
   - `registry.get(METER_ACQUIRED).tag(TAG_LOCK_NAME, "test-lock").counter().count()` ≥ 1
   - `registry.get(METER_EXECUTION_DURATION).tag(TAG_LOCK_NAME, "test-lock").timer().count()` ≥ 1
   - `registry.find(METER_ACTIVE).tag(TAG_LOCK_NAME, "test-lock").gauge()?.value()` == 0.0 (메서드 반환 후)
6. `backend 예외 시 lock.not.acquired reason=BACKEND_ERROR 증가` — Mock LeaderElection 이 throw → `tag(TAG_REASON, "BACKEND_ERROR")` counter 증가 검증
7. `leaderMicrometerHealthContributor 빈 등록 검증` — `Health.up()` + `details["metrics.registered"]` 존재

`junit-platform.properties` 메모리 규칙 + Kover 60% (메모리 규칙 `feedback_kover_unit_test_only_threshold.md`).

**의존**: T4, T6

---

### T9. [complexity: medium] Boot4 통합 테스트

**파일**: `leader-spring-boot4/src/test/kotlin/io/bluetape4k/leader/spring/boot4/metrics/LeaderMicrometerAutoConfigurationBoot4Test.kt`

**작업**: T8과 동일한 테스트 케이스를 아래 목록으로 명시 구현. 패키지/클래스만 `boot4` 로 변경.

테스트 케이스 (HealthContributor 제외):
1. `MeterRegistry 빈 존재 시 MicrometerLeaderAopMetricsRecorder 자동 등록`
2. `MeterRegistry 빈 없을 때 recorder 빈 미등록`
3. `enabled=false 시 빈 미등록`
4. `사용자 정의 LeaderAopMetricsRecorder가 우선`
5. `LeaderElectionAspect 통과 시 attempts+acquired+timer+active 전체 검증` — T8 #5와 동일 assertion (4가지 모두 검증)
6. `backend 예외 시 lock.not.acquired reason=BACKEND_ERROR 증가`

> **Boot4 차이점**: AspectJ post-compile weaving 환경. `@LeaderElection` 메서드에 `open` 불필요. Boot4 `LeaderAopAutoConfiguration` 클래스 FQN은 `io.bluetape4k.leader.spring.boot4.aop.autoconfigure.LeaderAopAutoConfiguration` (boot3와 다름 — 패키지 확인 필수).

**의존**: T5, T6

---

### T10. [complexity: low] junit-platform.properties + logback-test.xml (leader-micrometer)

**파일**:
- `leader-micrometer/src/test/resources/junit-platform.properties`
- `leader-micrometer/src/test/resources/logback-test.xml`

**작업**: 메모리 규칙 `feedback_junit_platform_per_class.md` 표준 적용.

```properties
junit.jupiter.testinstance.lifecycle.default=per_class
junit.jupiter.execution.parallel.enabled=false
```

`logback-test.xml`은 다른 모듈의 기존 파일 그대로 복사. `log.warn` 카디널리티 경고가 테스트 로그에 출력되도록 최소 WARN 레벨 설정.

**의존**: 없음 ← T7 실행 **전에** 반드시 완료해야 함

---

### T11. [complexity: low] KDoc 정비 + README.md + README.ko.md

**파일**:
- `leader-micrometer/README.md`
- `leader-micrometer/README.ko.md`

**작업** (둘 다 동일 구조 — 영/한):

1. **Overview** — leader-aop의 SPI(`LeaderAopMetricsRecorder`)에 대한 Micrometer 기반 기본 구현체 소개
2. **Installation** — Gradle 의존성 (`io.github.bluetape4k.leader:bluetape4k-leader-micrometer`)
3. **Usage**
   - Spring Boot 자동 설정 (별도 코드 불필요 — `MeterRegistry` 빈만 있으면 활성)
   - 수동 등록 예시 (non-Spring 환경): `MicrometerLeaderAopMetricsRecorder(registry)` → `LeaderElectionAspect(... recorders = listOf(recorder))`
   - `registerMetricsFor("job-a", "job-b")` 사전 등록 권장 (대시보드 NaN 방지)
   - `deregisterMetricsFor` 호출 시점 가이드 (동적 SpEL 사용 시 잡 제거 직후)
4. **Metrics Reference** — Spec §3.1 표 그대로 인용 (메터 6종 + 태그)
5. **Cardinality Warning**
   - `lock.name` 동적 SpEL 사용 시 카디널리티 폭발 위험
   - `exception` 태그는 `simpleName` 사용 (anonymous class → "Unknown")
   - 신규 lockName 등록 시 `log.warn` 출력 안내
6. **Multi-Instance Aggregation**
   - `leader.aop.active` 는 JVM-local
   - PromQL 권장: `max by (lock_name) (leader_aop_active)` (sum 사용 시 인스턴스 수 × 실제값)
7. **Configuration**
   - `bluetape4k.leader.aop.metrics.enabled` (default true) — false 설정 시 빈 미등록
8. **Custom Recorder Override** — `@ConditionalOnMissingBean` 기반, 사용자 정의 recorder 가 우선
9. **Out of Scope** — Observation API / acquire_time Timer / Boot4 HealthIndicator (Spec §5)

또한 `MicrometerLeaderAopMetricsRecorder` / `registerMetricsFor` / `deregisterMetricsFor` 의 KDoc 에는 동일한 카디널리티 경고와 multi-instance 가이드를 1줄씩 첨부.

**의존**: T3, T4, T5

---

## 후행 검증 단계 (PR 직전)

이슈 `#75 DoD` 와 메모리 규칙 (`feedback_pr_code_review.md`, `feedback_workflow_review_before_docs.md`) 에 따라 다음 순서를 강제한다.

1. **컴파일 + 테스트** — `./gradlew :leader-micrometer:test :leader-spring-boot3:test :leader-spring-boot4:test`
2. **Detekt** — `./gradlew detekt`
3. **Kover 커버리지** — `leader-micrometer 80%`, `boot3/4 60%` (`feedback_coverage_kover.md`, `feedback_kover_unit_test_only_threshold.md`)
4. **6중 코드 리뷰** — `bluetape4k-design Step 6-R` 6-Tier 리뷰 실행, **CRITICAL/HIGH 0** 확인
5. **README/KDoc 갱신** — 리뷰 후 API 변경이 있을 경우 T11 재실행 (Step 6 → Step 6-R 후 재진입)
6. **PR 생성** — Korean conventional commit prefix (`feat: leader-micrometer ...`)

---

## 의존 그래프 요약

```
T1 (gradle) ──┬─→ T3 (recorder) ──┬─→ T4 (boot3 autoconfig) ──┬─→ T6 (imports) ──┬─→ T8 (boot3 test)
T2 (names)  ──┘                   │                           │                  └─→ T9 (boot4 test)
T10 (junit) ──────────────────────│───────────────────────────│──────────────────→ T7 (unit test)
                                  ├─→ T5 (boot4 autoconfig) ──┘
                                  └─→ T11 (README + KDoc)
```

병렬 가능 그룹:
- **T1 + T2 + T10** (선행 없음 — T10은 T7 실행 전에 완료 필수)
- **T4 + T5 + T7** (T1+T2+T3+T10 완료 후)
- **T8 + T9** (T4/T5/T6 완료 후)
- **T11** (T3+T4+T5 완료 후)

---

## 라우팅 요약

| Task | Complexity | 추천 모델 |
|---|---|---|
| T1 | low | Haiku |
| T2 | low | Haiku |
| T3 | high | **Opus** (Gauge 람다 / deregister / 음수 방지 / 카디널리티 로깅 / thread-safety) |
| T4 | medium | Sonnet |
| T5 | medium | Sonnet |
| T6 | medium | Sonnet |
| T7 | medium | Sonnet |
| T8 | medium | Sonnet |
| T9 | medium | Sonnet |
| T10 | low | Haiku |
| T11 | low | Haiku |
