# Issue #602 최근 획득 실패 health window 구현 계획

> **Agent 작업 지침:** 각 작업을 순서대로 구현할 때 `test-driven-development`와 `bluetape-kotlin-patterns`를 사용합니다. 진행 상태는 체크박스(`- [ ]`)로 추적합니다.

**목표:** 기존 Spring Boot AOP recorder 계약으로 관찰한 `BACKEND_ERROR` 획득 실패를 bounded time window로 집계하고, 같은 상태 복사본을 readiness health detail과 `leaderElection` Actuator 응답에 노출한다.

**아키텍처:** `leader-spring-boot`에 core API를 변경하지 않는 Spring 전용 recorder를 추가한다. recorder는 timestamp만 고정 용량으로 보관하고, readiness indicator와 Actuator endpoint는 동일한 immutable `LeaderAcquisitionFailureView`를 읽는다. 기존 election decision, normal contention semantics, readiness `Status`, Micrometer 이름·tag·counter semantics는 유지한다.

**기술 스택:** Kotlin 2.3, Java 25, Spring Boot 4.1 Actuator, Spring `ApplicationContextRunner`, JUnit 5, MockK, bluetape4k assertions, Gradle, Java `Clock`/`Duration`, `javap` ABI 확인, Markdown/Korean terminology audit.

---

## 승인 범위와 실행 경계

- Issue: #602 `feat(leader-spring-boot): 최근 획득 실패 health window 추가`
- Epic: #700, train `SPRING-S-01`, 후행 Issue #603
- 실행 worktree: `/Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-epic-spring-s-01-health-window`
- branch: `feat/epic-spring-s-01-health-window`
- base: `origin/develop` `c17eb99fe7611f50802819512013b0c58d624e4f`
- 승인된 설계: [`2026-08-23-issue-602-health-window-design.md`](../specs/2026-08-23-issue-602-health-window-design.md)
- 설계 커밋: `6687d104db0962d1fdc64b8c237744a8f52edf76`
- baseline 근거: `LeaderElectionReadinessHealthIndicatorTest` 7개 focused test가 `BUILD SUCCESSFUL`로 통과했다. 전체 module test는 baseline에서 120초 동안 출력 없이 종료되지 않아 통과로 간주하지 않았고, 구현 후 별도로 재실행한다.
- `.workflow-inputs/`는 workflow helper 입력 전용 untracked 파일이므로 commit하지 않는다.
- 이 계획 승인 전에는 Kotlin production source, test source, README, manual, review, lesson을 수정하지 않는다.
- 새 dependency, core public API, `LeaderElectionListener`, Micrometer metric, backend lock enumeration, Ktor surface, persistence, retry, alert, background cleanup thread, readiness status downgrade는 만들지 않는다.
- 새 exception test는 `io.bluetape4k.assertions.assertFailsWith`만 사용한다.
- 모든 구현 commit은 Lore 형식의 한국어 intent와 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer를 포함한다.

## 파일 구조

### 새 파일

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderAcquisitionFailureWindow.kt` — timestamp retention, pruning, overflow 상태, public 상태 복사본 타입
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionAcquisitionFailureAutoConfiguration.kt` — AOP보다 먼저 recorder bean을 만드는 auto-configuration
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderAcquisitionFailureWindowTest.kt` — 분류, 경계, 용량, 동시성 단위 테스트
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpointTest.kt` — endpoint 상태 복사본과 생성자/copy 호환성 테스트
- `docs/review/2026-08-23-issue-602-health-window-review.md` — six-lens 자체 review evidence
- `docs/lessons/2026-08-23-issue-602-health-window.md` — 구현 교훈과 재발 방지 규칙

### 수정 파일

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderObservabilityProperties.kt` — `acquisitionFailureWindow` property와 기존 2-인자 constructor/copy 호환성
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicator.kt` — health detail에 같은 window view를 추가하되 status 계산은 유지
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthAutoConfiguration.kt` — window bean 주입과 기존 fallback constructor 유지
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpoint.kt` — response field와 window view 주입
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActuatorAutoConfiguration.kt` — 선택된 endpoint에 window 연결
- `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — 새 auto-configuration을 `LeaderAopAutoConfiguration`보다 앞에 배치
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicatorTest.kt` — 기본 detail, failure detail, status 불변, contention 제외
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfigurationTest.kt` — property binding, recorder 조건, auto-configuration order, endpoint/readiness 연결
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActuatorHttpPathTest.kt` — JSON response에 새 상태 복사본이 포함되는지 확인
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/LeaderPropertiesBindingTest.kt` — YAML key, default, invalid duration
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectTest.kt` — recorder 예외 격리 회귀 evidence 보강
- `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md` — 설정과 운영 해석
- `docs/manual/en/frameworks/spring-boot.md`, `docs/manual/ko/frameworks/spring-boot.md` — Spring Boot manual의 readiness/Actuator 계약
- `docs/manual/en/guides/observability-and-operations.md`, `docs/manual/ko/guides/observability-and-operations.md` — signal, lower-bound, overflow, non-decision 해석

## 공통 TDD·검증 규칙

각 task는 다음 순서를 지킨다.

1. 실패하는 테스트를 먼저 작성한다.
2. 해당 focused test를 실행해 새 계약이 없어서 실패하는지 읽는다.
3. 테스트를 통과시키는 최소 production 변경만 한다.
4. focused test와 영향 module test를 다시 실행한다.
5. `git diff --check`와 변경 파일 목록을 확인한다.
6. 독립된 Lore commit을 만든다.

`BACKEND_ERROR`는 `LeaderAopMetricsRecorder.onLockNotAcquired`의 유일한 집계 입력이다. `CONTENTION`과 `FAIL_OPEN_FORCED`는 집계하지 않는다. recorder의 내부 오류는 기존 AOP `fanOut` 격리 경계를 벗어나지 않아야 하며, health/endpoint view 오류도 해당 surface를 실패시키지 않고 빈 view로 대체한다.

## Task 0: baseline과 구현 전 계약 고정

**Files:**

- Read: `docs/superpowers/specs/2026-08-23-issue-602-health-window-design.md`
- Read: `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/LeaderAopMetricsRecorder.kt`
- Read: `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/SkipReason.kt`
- Read: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt`
- Read: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderGroupElectionAspect.kt`

- [x] **Step 1: 설계와 현재 callback 경로를 다시 읽는다**

  `onLockNotAcquired(..., BACKEND_ERROR)`가 sync, async, suspend, Reactor, `Flow`, group 경로에서 이미 fan-out되는 것을 확인한다. 이번 구현에서는 AOP source를 수정하지 않고 기존 callback을 소비한다.

- [x] **Step 2: baseline focused test를 읽고 재사용 목록을 고정한다**

  다음 테스트를 최종 회귀 집합에 포함한다.

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests '*LeaderElectionReadinessHealthIndicatorTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 baseline: `SUCCESS: Executed 7 tests`, `BUILD SUCCESSFUL`. 전체 module test는 구현 후 다시 실행할 때만 완료 증거로 사용한다.

- [x] **Step 3: 계획 전용 상태를 변경하지 않는다**

  이 task에는 production/test/doc 파일을 수정하지 않는다. `git status --short`에서 계획 승인 전에는 `.workflow-inputs/` 외 새 변경이 없어야 한다.

## Task 1: bounded recorder와 상태 복사본의 RED/GREEN

**Files:**

- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderAcquisitionFailureWindowTest.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderAcquisitionFailureWindow.kt`

- [ ] **Step 1: 실패하는 window 테스트를 작성한다**

  테스트는 fixed `Clock`과 작은 capacity를 사용해 다음 계약을 직접 고정한다.

  ```kotlin
  @Test
  fun `backend error is counted while contention and fail open are ignored`() {
      val window = LeaderAcquisitionFailureWindow(
          window = Duration.ofMinutes(5),
          clock = Clock.fixed(now, ZoneOffset.UTC),
          capacity = 4,
      )
      val options = LeaderElectionOptions()

      window.onLockNotAcquired("job-a", options, SkipReason.CONTENTION)
      window.onLockNotAcquired("job-b", options, SkipReason.FAIL_OPEN_FORCED)
      window.onLockNotAcquired("job-c", options, SkipReason.BACKEND_ERROR)

      window.view().let { view ->
          view.count shouldBeEqualTo 1
          view.lastFailureAt shouldBeEqualTo now
          view.overflowed.shouldBeFalse()
      }
  }

  @Test
  fun `lower boundary is included and older timestamp is pruned`() {
      val clock = MutableClock(now)
      val window = LeaderAcquisitionFailureWindow(Duration.ofSeconds(10), clock, capacity = 4)
      val options = LeaderElectionOptions()

      clock.current = now.minusSeconds(10)
      window.onLockNotAcquired("boundary", options, SkipReason.BACKEND_ERROR)
      clock.current = now.minusSeconds(11)
      window.onLockNotAcquired("expired", options, SkipReason.BACKEND_ERROR)
      clock.current = now

      window.view().count shouldBeEqualTo 1
      window.view().lastFailureAt shouldBeEqualTo now.minusSeconds(10)
  }

  @Test
  fun `capacity eviction reports lower bound and clears overflow after expiry`() {
      val clock = MutableClock(now)
      val window = LeaderAcquisitionFailureWindow(Duration.ofMinutes(5), clock, capacity = 2)
      val options = LeaderElectionOptions()

      repeat(3) { window.onLockNotAcquired("job-$it", options, SkipReason.BACKEND_ERROR) }
      window.view().let { view ->
          view.count shouldBeEqualTo 2
          view.capacity shouldBeEqualTo 2
          view.overflowed.shouldBeTrue()
      }

      clock.current = now.plus(Duration.ofMinutes(6))
      window.view().let { view ->
          view.count shouldBeEqualTo 0
          view.lastFailureAt shouldBeNull()
          view.overflowed.shouldBeFalse()
      }
  }

  @Test
  fun `invalid window and capacity fail fast`() {
      assertFailsWith<IllegalArgumentException> {
          LeaderAcquisitionFailureWindow(Duration.ZERO, Clock.fixed(now, ZoneOffset.UTC), 4)
      }
      assertFailsWith<IllegalArgumentException> {
          LeaderAcquisitionFailureWindow(Duration.ofSeconds(1), Clock.fixed(now, ZoneOffset.UTC), 0)
      }
  }

  @Test
  fun `clock failure is swallowed by best effort recorder`() {
      val window = LeaderAcquisitionFailureWindow(Duration.ofSeconds(5), ThrowingClock(), capacity = 4)

      window.onLockNotAcquired("job", LeaderElectionOptions(), SkipReason.BACKEND_ERROR)

      window.view(Instant.parse("2026-07-15T00:00:00Z")).count shouldBeEqualTo 0
  }
  ```

  `MutableClock`은 테스트 파일 안에 `Clock`의 `instant()`와 `getZone()`을 구현한 private fixture로 작성한다. recorder 격리는 다음 fixture로 확인한다.

  ```kotlin
  private class ThrowingClock : Clock() {
      override fun instant(): Instant = throw IllegalStateException("clock unavailable")
      override fun getZone(): ZoneId = ZoneOffset.UTC
      override fun withZone(zone: ZoneId): Clock = this
  }
  ```

  동시성 테스트는 `Executors.newFixedThreadPool(8)`에서 800개 `BACKEND_ERROR` callback을 보낸 뒤 `view().count <= capacity`와 예외 미발생을 확인하고 executor를 `shutdown()`한다.

- [ ] **Step 2: focused RED를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderAcquisitionFailureWindowTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 결과: 새 recorder/view 타입이 없어서 compile 또는 test discovery가 실패한다. 실패가 아닌 기존 테스트 통과만 보이면 새 test source가 실제로 포함됐는지 먼저 확인한다.

- [ ] **Step 3: 최소 bounded 구현을 작성한다**

  구현 계약은 다음 시그니처와 동작을 따른다.

  ```kotlin
  public data class LeaderAcquisitionFailureView(
      val count: Int,
      val lastFailureAt: Instant?,
      val window: Duration,
      val capacity: Int,
      val overflowed: Boolean,
  ) {
      public companion object {
          public val DefaultWindow: Duration = Duration.ofMinutes(5)
          public const val DefaultCapacity: Int = 1024

          public fun empty(
              window: Duration = DefaultWindow,
              capacity: Int = DefaultCapacity,
          ): LeaderAcquisitionFailureView = LeaderAcquisitionFailureView(
              count = 0,
              lastFailureAt = null,
              window = window,
              capacity = capacity,
              overflowed = false,
          )
      }
  }

  internal class LeaderAcquisitionFailureWindow(
      private val window: Duration,
      private val clock: Clock = Clock.systemUTC(),
      private val capacity: Int = LeaderAcquisitionFailureView.DefaultCapacity,
  ) : LeaderAopMetricsRecorder {
      private val monitor = Any()
      private val timestamps = ArrayDeque<Instant>()
      private var overflowed = false

      override fun onLockNotAcquired(
          name: String,
          options: LeaderElectionOptions,
          reason: SkipReason,
      ) {
          if (reason != SkipReason.BACKEND_ERROR) return
          runCatching {
              synchronized(monitor) {
                  timestamps.addLast(clock.instant())
                  while (timestamps.size > capacity) {
                      timestamps.removeFirst()
                      overflowed = true
                  }
              }
          }
      }

      fun view(now: Instant = clock.instant()): LeaderAcquisitionFailureView =
          synchronized(monitor) {
              val boundary = now.minus(window)
              timestamps.removeIf { it.isBefore(boundary) }
              if (timestamps.isEmpty()) overflowed = false
              LeaderAcquisitionFailureView(
                  count = timestamps.size,
                  lastFailureAt = timestamps.maxOrNull(),
                  window = window,
                  capacity = capacity,
                  overflowed = overflowed,
              )
      }
  }
  ```

  실제 source에서는 `java.util.ArrayDeque`, `java.time.Duration`, `java.time.Instant`, `java.time.Clock`, 기존 `LeaderAopMetricsRecorder`, `LeaderElectionOptions`, `SkipReason`을 import한다. `window`는 positive finite, `capacity`는 positive인지 `init`에서 검증한다. recorder는 lock name, options, exception, backend, leader identity를 저장하지 않는다. `view`는 mutable collection을 반환하지 않고, clock이 뒤로 이동해도 저장 timestamp를 clock 순서로 재정렬하거나 삭제하지 않는다. timestamp의 가장 최근 값은 `maxOrNull()`로 계산한다. `LeaderAcquisitionFailureWindow`는 `internal`로 유지하고 `LeaderAcquisitionFailureView`만 response field에 필요한 public immutable type으로 둔다. 따라서 public health/endpoint constructor에는 internal class를 노출하지 않는다.

- [ ] **Step 4: focused GREEN과 memory bound를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderAcquisitionFailureWindowTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 결과: 모든 window test가 통과하고 concurrent callback 이후 `count <= capacity`, raw lock name/exception이 view에 없음을 확인한다.

- [ ] **Step 5: recorder 변경을 commit한다**

  ```bash
  git add leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderAcquisitionFailureWindow.kt \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderAcquisitionFailureWindowTest.kt
  git diff --cached --check
  git commit -m "feat: 최근 backend 획득 실패를 bounded window로 관찰한다"
  ```

  commit body의 `Tested:`에는 focused RED 후 GREEN 명령과 결과를, `Not-tested:`에는 아직 Spring context/health/endpoint를 실행하지 않았다는 사실을 적는다.

## Task 2: property compatibility와 AOP 이전 auto-configuration

**Files:**

- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderObservabilityProperties.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionAcquisitionFailureAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/LeaderPropertiesBindingTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfigurationTest.kt`

- [ ] **Step 1: property와 condition RED 테스트를 먼저 추가한다**

  `LeaderPropertiesBindingTest`에 다음을 추가한다.

  ```kotlin
  @Test
  fun `acquisition failure window binds with five minute default`() {
      val defaults = Binder(MapConfigurationPropertySource(emptyMap<String, String>()))
          .bindOrCreate<LeaderProperties>("bluetape4k.leader")
      defaults.observability.health.acquisitionFailureWindow shouldBeEqualTo Duration.ofMinutes(5)

      val source = MapConfigurationPropertySource(
          mapOf("bluetape4k.leader.observability.health.acquisition-failure-window" to "2m")
      )
      Binder(source).bindAs<LeaderProperties>("bluetape4k.leader").get()
          .observability.health.acquisitionFailureWindow shouldBeEqualTo Duration.ofMinutes(2)
  }

  @Test
  fun `acquisition failure window rejects zero negative and infinite duration`() {
      listOf(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(Long.MAX_VALUE)).forEach { value ->
          assertFailsWith<IllegalArgumentException> {
              LeaderObservabilityHealthProperties(acquisitionFailureWindow = value)
          }
      }

      internal companion object {
          internal val DefaultWindow: Duration = LeaderAcquisitionFailureView.DefaultWindow
          internal const val DefaultCapacity: Int = LeaderAcquisitionFailureView.DefaultCapacity
      }
  }
  ```

  `LeaderElectionObservabilityAutoConfigurationTest`에는 default recorder bean, `observability.enabled=false` 부재, custom `2m` property 전달, `AutoConfiguration.imports`에서 새 class index가 `LeaderAopAutoConfiguration` index보다 작은지 확인하는 테스트를 추가한다. context test에서는 `ctx.getBean(LeaderAcquisitionFailureWindow::class.java)`로 bean을 읽고 `view().window`를 검증한다.

- [ ] **Step 2: RED를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.LeaderPropertiesBindingTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 결과: 새 property, bean, auto-configuration class가 없거나 imports 순서가 없어 실패한다.

- [ ] **Step 3: 기존 property constructor/copy ABI를 보존하면서 field를 추가한다**

  `LeaderObservabilityHealthProperties` primary constructor를 다음 세 field로 확장한다.

  ```kotlin
  data class LeaderObservabilityHealthProperties(
      val enabled: Boolean = false,
      val leaseWarningThreshold: Duration = Duration.ofSeconds(10),
      val acquisitionFailureWindow: Duration = Duration.ofMinutes(5),
  ) : Serializable
  ```

  `init`에서 `leaseWarningThreshold`는 non-negative, `acquisitionFailureWindow`는 positive finite인지 검증한다. 기존 공개 2-인자 `(Boolean, Duration)` constructor를 명시적 secondary constructor로 유지하고, 기존 2-인자 `copy(enabled, leaseWarningThreshold)` 진입점을 `acquisitionFailureWindow` 보존 방식으로 명시한다. Kotlin compiler가 기존 `copy$default` descriptor를 제거하는지 `javap`로 확인하고, `LeaderObservabilityProperties`에 이미 있는 `@JvmStatic` compatibility pattern과 같은 mask bit (`enabled=0x001`, `leaseWarningThreshold=0x002`)를 사용해 기존 Java/Kotlin caller가 default copy를 계속 호출하게 한다. 새 field의 default는 `DefaultAcquisitionFailureWindow = Duration.ofMinutes(5)` 하나로 관리한다.

- [ ] **Step 4: 새 auto-configuration과 import 순서를 구현한다**

  새 class는 다음 계약을 그대로 따른다.

  ```kotlin
  @AutoConfiguration(before = [LeaderAopAutoConfiguration::class])
  @ConditionalOnClass(LeaderAopMetricsRecorder::class)
  @ConditionalOnProperty(
      prefix = "bluetape4k.leader.observability",
      name = ["enabled"],
      havingValue = "true",
      matchIfMissing = true,
  )
  @EnableConfigurationProperties(LeaderProperties::class)
  class LeaderElectionAcquisitionFailureAutoConfiguration {

      @Bean
      @ConditionalOnMissingBean
      @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
      internal fun leaderAcquisitionFailureWindow(
          properties: LeaderProperties,
      ): LeaderAcquisitionFailureWindow = LeaderAcquisitionFailureWindow(
          window = properties.observability.health.acquisitionFailureWindow,
      )
  }
  ```

  `AutoConfiguration.imports`에서는 `LeaderMicrometerAutoConfiguration`와 `LeaderObservationAutoConfiguration` 뒤, `LeaderAopAutoConfiguration` 앞에 fully-qualified class를 추가한다. `@ConditionalOnBean`으로 AOP를 강제하지 않는다. AOP가 `ObjectProvider<LeaderAopMetricsRecorder>`를 생성할 때 recorder를 수집하도록 하고, observability parent switch가 꺼진 context에서는 bean을 만들지 않는다.

- [ ] **Step 5: property/context GREEN과 ABI를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.LeaderPropertiesBindingTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ./gradlew :bluetape4k-leader-spring-boot:compileKotlin \
    --no-configuration-cache --no-build-cache --console=plain
  javap -classpath leader-spring-boot/build/classes/kotlin/main \
    io.bluetape4k.leader.spring.properties.LeaderObservabilityHealthProperties
  ```

  기대 결과: default/custom/disabled condition이 통과하고, `javap` output에 기존 2-인자 constructor와 2-인자 `copy` compatibility entry가 남는다. `javap`가 기대 descriptor를 보이지 않으면 implementation commit 전에 constructor/copy 선언을 수정한다.

- [ ] **Step 6: property/auto-configuration을 commit한다**

  ```bash
  git add leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderObservabilityProperties.kt \
    leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionAcquisitionFailureAutoConfiguration.kt \
    leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/LeaderPropertiesBindingTest.kt \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfigurationTest.kt
  git diff --cached --check
  git commit -m "feat: Spring 관측 설정과 AOP 이전 failure recorder를 연결한다"
  ```

## Task 3: readiness health detail을 window view와 연결

**Files:**

- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicator.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicatorTest.kt`

- [ ] **Step 1: 기존 detail 기대값과 새 실패 관찰 RED 테스트를 추가한다**

  빈 registry 기대 detail에 아래 다섯 key를 추가하고, fixed clock으로 recorder callback을 주입한다.

  ```kotlin
  @Test
  fun `backend acquisition failure is detail only and does not downgrade status`() {
      val window = LeaderAcquisitionFailureWindow(Duration.ofMinutes(5), clock, capacity = 4)
      window.onLockNotAcquired("redis-prod-01", LeaderElectionOptions(), SkipReason.BACKEND_ERROR)

      val health = LeaderElectionReadinessHealthIndicator(
          leaderElector = elector,
          registry = LeaderElectionStatusRegistry(),
          leaseWarningThreshold = warningThreshold,
          clock = clock,
          acquisitionFailureWindow = window,
      ).health()

      health.status shouldBeEqualTo Status.UP
      health.details["recentAcquisitionFailures"] shouldBeEqualTo 1
      health.details["lastAcquisitionFailureAt"] shouldBeEqualTo now
      health.details["acquisitionFailureWindow"] shouldBeEqualTo "PT5M"
      health.details["acquisitionFailureWindowCapacity"] shouldBeEqualTo 4
      health.details["acquisitionFailureWindowOverflowed"] shouldBeEqualTo false
      health.details.toString().contains("redis-prod-01").shouldBeFalse()
  }

  @Test
  fun `contention is absent from recent acquisition failure detail`() {
      val window = LeaderAcquisitionFailureWindow(Duration.ofMinutes(5), clock, capacity = 4)
      window.onLockNotAcquired("contention-job", LeaderElectionOptions(), SkipReason.CONTENTION)

      val health = indicator(acquisitionFailureWindow = window).health()

      health.details["recentAcquisitionFailures"] shouldBeEqualTo 0
      health.details["lastAcquisitionFailureAt"] shouldBeNull()
  }
  ```

  state read failure, unsupported state, expiring lease와 동시에 failure view를 읽는 test도 추가한다. status는 각각 기존 `DOWN`, `UNKNOWN`, `OUT_OF_SERVICE`를 유지하고 raw backend exception message를 새 detail에 넣지 않는다.

- [ ] **Step 2: focused RED를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionReadinessHealthIndicatorTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 결과: constructor parameter와 새 detail key가 아직 없어 compile/test assertion이 실패한다.

- [ ] **Step 3: indicator에 optional window와 safe view를 구현한다**

  `LeaderElectionReadinessHealthIndicator`의 기존 public `(LeaderElectionState, LeaderElectionStatusRegistry, Duration, Clock)` constructor는 그대로 secondary constructor로 보존한다. internal private primary constructor만 `LeaderAcquisitionFailureWindow?`를 보유하고, module-internal secondary constructor와 `fromSelectedState(..., acquisitionFailureWindow)`가 새 bean을 전달한다. `fromSelectedState`는 기존 `@JvmSynthetic internal` 경계를 유지하고 window를 마지막 optional argument로 받는다. 이렇게 public JVM API에 internal window class를 노출하지 않는다.

  다음 code block은 existing `doHealthCheck`/selector field를 그대로 둔 constructor section이다.

  ```kotlin
  class LeaderElectionReadinessHealthIndicator private constructor(
      leaderElector: LeaderElectionState,
      registry: LeaderElectionStatusRegistry,
      leaseWarningThreshold: Duration,
      clock: Clock,
      private val acquisitionFailureWindow: LeaderAcquisitionFailureWindow?,
  ) : AbstractHealthIndicator("Leader election readiness check failed") {

  constructor(
      leaderElector: LeaderElectionState,
      registry: LeaderElectionStatusRegistry,
      leaseWarningThreshold: Duration,
      clock: Clock = Clock.systemUTC(),
  ) : this(leaderElector, registry, leaseWarningThreshold, clock, null)

  internal constructor(
      leaderElector: LeaderElectionState,
      registry: LeaderElectionStatusRegistry,
      leaseWarningThreshold: Duration,
      clock: Clock,
      acquisitionFailureWindow: LeaderAcquisitionFailureWindow,
  ) : this(leaderElector, registry, leaseWarningThreshold, clock, acquisitionFailureWindow)
  }
  ```

  `doHealthCheck` 시작 시 `safeAcquisitionFailureView()`를 한 번 호출하고, `baseDetails()`와 unsupported/state-read-failure/normal detail 모두에 다음 map을 합친다.

  ```kotlin
  private fun acquisitionFailureDetails(): Map<String, Any?> = runCatching {
      val view = acquisitionFailureWindow?.view() ?: LeaderAcquisitionFailureView.empty()
      mapOf(
          DETAIL_RECENT_ACQUISITION_FAILURES to view.count,
          DETAIL_LAST_ACQUISITION_FAILURE_AT to view.lastFailureAt,
          DETAIL_ACQUISITION_FAILURE_WINDOW to view.window.toString(),
          DETAIL_ACQUISITION_FAILURE_WINDOW_CAPACITY to view.capacity,
          DETAIL_ACQUISITION_FAILURE_WINDOW_OVERFLOWED to view.overflowed,
      )
  }.getOrElse {
      mapOf(
          DETAIL_RECENT_ACQUISITION_FAILURES to 0,
          DETAIL_LAST_ACQUISITION_FAILURE_AT to null,
          DETAIL_ACQUISITION_FAILURE_WINDOW to LeaderAcquisitionFailureWindow.DefaultWindow.toString(),
          DETAIL_ACQUISITION_FAILURE_WINDOW_CAPACITY to LeaderAcquisitionFailureWindow.DefaultCapacity,
          DETAIL_ACQUISITION_FAILURE_WINDOW_OVERFLOWED to false,
      )
  }
  ```

  health `Status` 분기는 기존 `failedLockNames`와 `expiringLockNames`만 사용한다. `recentAcquisitionFailures`는 status를 변경하지 않는다. detail에는 lock name, backend lock enumeration, exception message를 추가하지 않는다. 실제 logger가 필요하면 기존 모듈 logger 규칙을 사용하고 exception message를 detail에 넣지 않는다.

- [ ] **Step 4: auto-configuration에서 window를 주입한다**

  `leaderElectionReadiness` bean method에 `LeaderAcquisitionFailureWindow`를 필수 parameter로 추가하고 `internal fun`으로 선언해 internal type이 public API에 새지 않게 한다. `fromSelectedState(..., acquisitionFailureWindow = window)`로 전달한다. 기존 `LeaderElectionReadinessHealthAutoConfiguration`의 registry/state/property 조건과 health enablement는 변경하지 않는다. observability disabled로 window가 없는 수동 구성의 기존 public indicator 생성은 secondary constructor로 계속 동작하게 한다.

- [ ] **Step 5: focused GREEN과 기존 status 회귀를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionReadinessHealthIndicatorTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 결과: 기존 7개 readiness test와 새 status/detail test가 통과하고, `UP`/`OUT_OF_SERVICE`/`DOWN`/`UNKNOWN` mapping이 그대로 유지된다.

- [ ] **Step 6: readiness 변경을 commit한다**

  ```bash
  git add leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicator.kt \
    leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthAutoConfiguration.kt \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicatorTest.kt
  git diff --cached --check
  git commit -m "feat: readiness detail에 최근 획득 실패 window를 표시한다"
  ```

## Task 4: `leaderElection` Actuator response와 호환성

**Files:**

- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpoint.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActuatorAutoConfiguration.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpointTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfigurationTest.kt`
- Modify: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActuatorHttpPathTest.kt`

- [ ] **Step 1: response shape와 compatibility RED 테스트를 작성한다**

  direct endpoint test는 fixed clock window에 한 번의 `BACKEND_ERROR`를 기록한 뒤 다음을 확인한다.

  ```kotlin
  @Test
  fun `endpoint returns same acquisition failure view without lock names`() {
      val window = LeaderAcquisitionFailureWindow(Duration.ofMinutes(5), clock, capacity = 4)
      window.onLockNotAcquired("tenant-secret-job", LeaderElectionOptions(), SkipReason.BACKEND_ERROR)
      val endpoint = LeaderElectionStatusEndpoint(elector, registry, window)

      val response = endpoint.leaderElectionStatus()

      response.acquisitionFailures.count shouldBeEqualTo 1
      response.acquisitionFailures.lastFailureAt shouldBeEqualTo now
      response.acquisitionFailures.window shouldBeEqualTo Duration.ofMinutes(5)
      response.acquisitionFailures.capacity shouldBeEqualTo 4
      response.acquisitionFailures.overflowed.shouldBeFalse()
      response.acquisitionFailures.toString().contains("tenant-secret-job").shouldBeFalse()
  }

  @Test
  fun `legacy response constructor and copy preserve empty acquisition view`() {
      val legacy = LeaderElectionStatusResponse(listOf(LeaderElectionLockStatus("job", "Empty", null, null)))
      legacy.acquisitionFailures.count shouldBeEqualTo 0
      legacy.copy(legacy.locks).acquisitionFailures shouldBeEqualTo legacy.acquisitionFailures
  }
  ```

  `javap` fixture는 기존 `LeaderElectionStatusResponse(List)`, `copy(List)`, 기존 4-인자 data constructor와 `LeaderElectionStatusEndpoint(LeaderElector, LeaderElectionStatusRegistry)`를 검사한다. HTTP test는 JSON에 `acquisitionFailures`, `recent`/`count` 값과 기존 backend/provider/lock 값을 함께 확인한다.

- [ ] **Step 2: RED를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionStatusEndpointTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionActuatorHttpPathTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  기대 결과: endpoint constructor, response field, view serialization이 아직 없어 실패한다.

- [ ] **Step 3: endpoint와 response를 최소 변경한다**

  `LeaderElectionStatusEndpoint`의 기존 public `(LeaderElector, LeaderElectionStatusRegistry)` constructor는 그대로 secondary constructor로 보존한다. internal private primary constructor만 nullable window를 보유하고, module-internal 3-인자 constructor와 `fromSelectedState`가 window를 전달한다. `leaderElectionStatus()`는 기존 lock state 계산을 변경하지 않고 response 마지막 field에 `safeView()` 결과를 넣는다.

  다음 code block은 existing `leaderElectionStatus`/selector field를 그대로 둔 constructor section이다.

  ```kotlin
  class LeaderElectionStatusEndpoint private constructor(
      leaderElector: LeaderElector,
      registry: LeaderElectionStatusRegistry,
      private val acquisitionFailureWindow: LeaderAcquisitionFailureWindow?,
  ) {

      constructor(
          leaderElector: LeaderElector,
          registry: LeaderElectionStatusRegistry,
      ) : this(leaderElector, registry, null)

      internal constructor(
          leaderElector: LeaderElector,
          registry: LeaderElectionStatusRegistry,
          acquisitionFailureWindow: LeaderAcquisitionFailureWindow,
      ) : this(leaderElector, registry, acquisitionFailureWindow)
  }
  ```

  `LeaderElectionStatusResponse`에는 다음 field를 마지막에 추가한다.

  ```kotlin
  val acquisitionFailures: LeaderAcquisitionFailureView = LeaderAcquisitionFailureView.empty(),
  ```

  기존 단일 인자 constructor와 `copy(locks)`를 새 field 보존 방식으로 유지한다. 기존 공개 4-인자 constructor와 copy descriptor가 `javap`에서 사라지면 explicit overload를 추가한다. 새 view는 public immutable data class이지만 lock name, backend name, exception message를 field로 갖지 않는다.

- [ ] **Step 4: Actuator auto-configuration에 같은 bean을 전달한다**

  `leaderElectionStatusEndpoint` bean method를 `internal fun`으로 유지하면서 `LeaderAcquisitionFailureWindow` parameter를 추가하고 selected-state factory에 전달한다. endpoint property, registry/state condition, endpoint exposure contract는 그대로 둔다. compatibility용 기존 two-argument factory method는 window 없는 endpoint를 생성하도록 유지한다.

- [ ] **Step 5: endpoint GREEN과 ABI를 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionStatusEndpointTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionActuatorHttpPathTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ./gradlew :bluetape4k-leader-spring-boot:compileKotlin \
    --no-configuration-cache --no-build-cache --console=plain
  javap -classpath leader-spring-boot/build/classes/kotlin/main \
    io.bluetape4k.leader.spring.observability.LeaderElectionStatusEndpoint \
    io.bluetape4k.leader.spring.observability.LeaderElectionStatusResponse
  ```

  기대 결과: HTTP path는 `/actuator/leaderElection` 그대로이고, 기존 response fields와 새 `acquisitionFailures`가 모두 JSON에 나타난다. legacy constructor/copy 호출과 새 constructor/copy가 함께 컴파일된다.

- [ ] **Step 6: endpoint 변경을 commit한다**

  ```bash
  git add leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpoint.kt \
    leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActuatorAutoConfiguration.kt \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionStatusEndpointTest.kt \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfigurationTest.kt \
    leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionActuatorHttpPathTest.kt
  git diff --cached --check
  git commit -m "feat: leaderElection 진단 응답에 failure window를 추가한다"
  ```

## Task 5: English/Korean 운영 문서와 writer gate

**Files:**

- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Modify: `docs/manual/en/frameworks/spring-boot.md`
- Modify: `docs/manual/ko/frameworks/spring-boot.md`
- Modify: `docs/manual/en/guides/observability-and-operations.md`
- Modify: `docs/manual/ko/guides/observability-and-operations.md`

- [ ] **Step 1: behavior가 고정된 뒤 문서 RED checklist를 실행한다**

  source와 tests에서 다음 reader-facing tokens가 실제 field/property 이름과 일치하는지 확인한다: `acquisition-failure-window`, `recentAcquisitionFailures`, `lastAcquisitionFailureAt`, `acquisitionFailureWindowCapacity`, `acquisitionFailureWindowOverflowed`, `BACKEND_ERROR`, `CONTENTION`, `FAIL_OPEN_FORCED`.

- [ ] **Step 2: EN/KO 문서를 같은 의미로 갱신한다**

  두 locale 모두 다음 내용을 포함한다.

  1. `bluetape4k.leader.observability.health.acquisition-failure-window`의 기본값 `5m`, positive finite 제약, fixed capacity `1024`를 YAML로 보여 준다.
  2. 집계 대상이 AOP `BACKEND_ERROR`이고 normal `CONTENTION`과 `FAIL_OPEN_FORCED`는 제외된다고 설명한다.
  3. `recentAcquisitionFailures`는 현재 window에 남은 retained count이며 capacity 초과 시 `acquisitionFailureWindowOverflowed=true`와 함께 전체 실패의 lower bound일 수 있음을 설명한다.
  4. `lastAcquisitionFailureAt`가 window 밖으로 만료되면 `null`이 되고, detail/response가 lock name·exception message를 보관하지 않는다고 설명한다.
  5. 최근 획득 실패만으로 readiness `UP`, `OUT_OF_SERVICE`, `DOWN`, `UNKNOWN` status를 바꾸지 않으며, 이 surface는 best-effort 관찰 정보라는 운영 경계를 설명한다.
  6. Actuator endpoint 보호와 dynamic lock name을 bounded registry로 운영해야 한다는 보안·cardinality 주의를 유지한다.

  English 문서는 English technical prose, Korean 문서는 자연스러운 한국어 기술 문체로 작성한다. 설정 키, JSON key, enum, URL, class/method 이름은 번역하지 않는다. README에서 이미 다룬 backend diagnostics 문단을 복제하지 말고 readiness/leader election failure window에 필요한 최소 설명만 추가한다.

- [ ] **Step 3: 문서 read-back과 terminology audit를 실행한다**

  ```bash
  git diff --check
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    leader-spring-boot/README.ko.md \
    docs/manual/ko/frameworks/spring-boot.md \
    docs/manual/ko/guides/observability-and-operations.md
  node scripts/check-readme-language-switches.mjs
  ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
  ```

  기대 결과: `Terminology audit passed ... findings=0`, README locale switch 검사 통과, manual contract test 통과. findings가 생기면 번역을 보강하고 같은 명령을 다시 실행한다.

- [ ] **Step 4: 문서 변경을 commit한다**

  ```bash
  git add leader-spring-boot/README.md leader-spring-boot/README.ko.md \
    docs/manual/en/frameworks/spring-boot.md docs/manual/ko/frameworks/spring-boot.md \
    docs/manual/en/guides/observability-and-operations.md \
    docs/manual/ko/guides/observability-and-operations.md
  git diff --cached --check
  git commit -m "docs: 최근 획득 실패 window 운영 해석을 설명한다"
  ```

## Task 6: module-wide 검증과 six-lens 자체 review

**Files:**

- Create: `docs/review/2026-08-23-issue-602-health-window-review.md`
- Create: `docs/lessons/2026-08-23-issue-602-health-window.md`
- Read/verify: all changed Kotlin, tests, metadata, README, manual files

- [ ] **Step 1: fresh targeted/module validation을 실행한다**

  먼저 focused set을 실행한다.

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderAcquisitionFailureWindowTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionReadinessHealthIndicatorTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionStatusEndpointTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' \
    --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionActuatorHttpPathTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  다음으로 기존 AOP failure-mode와 group failure-mode 회귀를 실행한다.

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspectTest' \
    --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspectFailureModeTest' \
    --tests 'io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspectTest' \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  마지막으로 module test와 detekt를 fresh run한다.

  ```bash
  ./gradlew :bluetape4k-leader-spring-boot:test \
    --no-configuration-cache --no-build-cache --console=plain
  ./gradlew :bluetape4k-leader-spring-boot:detekt \
    --no-configuration-cache --no-build-cache --console=plain
  ```

  module test가 120초 동안 출력 없이 멈추면 성공으로 표시하지 않는다. `colima status`, `docker context show`, `docker info`를 확인한 뒤 container 의존성이 없는 focused set을 먼저 재실행하고, 남은 timeout을 validation gap으로 review/DoD에 기록한다.

- [ ] **Step 2: static/ABI/document validation을 실행한다**

  ```bash
  git diff --check
  ./gradlew :bluetape4k-leader-spring-boot:compileKotlin \
    --no-configuration-cache --no-build-cache --console=plain
  javap -classpath leader-spring-boot/build/classes/kotlin/main \
    io.bluetape4k.leader.spring.properties.LeaderObservabilityHealthProperties \
    io.bluetape4k.leader.spring.observability.LeaderElectionReadinessHealthIndicator \
    io.bluetape4k.leader.spring.observability.LeaderElectionStatusEndpoint \
    io.bluetape4k.leader.spring.observability.LeaderElectionStatusResponse
  node scripts/check-readme-language-switches.mjs
  ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'
  ```

  `javap` 결과에는 기존 public constructor/copy compatibility entry가 남고, new view field/constructor가 명시되어야 한다. `git diff --check`가 실패하면 commit/PR 단계로 진행하지 않는다.

- [ ] **Step 3: six-lens 자체 review를 독립 pass로 수행한다**

  `docs/review/2026-08-23-issue-602-health-window-review.md`에 command, commit SHA, 결과, repair를 기록한다. 다음 여섯 관점을 각각 `PASS` 또는 구체적인 finding으로 채운다.

  | 관점 | 확인할 내용 |
  |---|---|
  | API/호환성 | property/endpoint/response constructor, `copy`, `javap`, JSON field 이름 |
  | 동시성/정확성 | synchronized prune/copy, boundary inclusion, backward clock, concurrent callback |
  | 성능/메모리 | O(capacity), no lock-name/exception storage, no background thread, AOP decision unchanged |
  | 보안/개인정보 | response/detail에 raw lock name·exception·leader identity가 새로 들어가지 않음, Actuator 보호 문서 |
  | Spring/Kotlin 통합 | auto-config import order, `ObjectProvider` collection timing, optional legacy constructors, health condition |
  | 운영/문서/테스트 | EN/KO parity, overflow lower-bound interpretation, status non-downgrade, focused/module/detekt evidence |

  P0/P1 finding은 0이어야 한다. finding이 있으면 해당 task source/test를 수정하고 focused test, diff check, review pass를 다시 실행한다.

  기존 `LeaderElectionAspectTest`의 metrics isolation test가 `onLockAttempt`만 다루면 `throwingRecorder.onLockNotAcquired(any(), any(), SkipReason.BACKEND_ERROR)`가 `RuntimeException("recorder failure")`를 던지는 fixture를 추가한다. 두 번째 recorder의 callback이 한 번 호출되고 aspect 결과가 원래 `null`/작업 결과를 유지하는지 확인해 `fanOut` best-effort 경계를 Issue #602 경로에서도 직접 증명한다.

- [ ] **Step 4: lesson을 작성하고 localization/read-back을 확인한다**

  `docs/lessons/2026-08-23-issue-602-health-window.md`에는 한국어로 다음을 기록한다: `BACKEND_ERROR`만 재사용한 이유, fixed capacity와 overflow lower-bound 해석, constructor/copy ABI 보존 방법, auto-configuration order 실수 방지 규칙, 실행한 검증 명령과 남은 gap. lock name과 exception message를 lesson 예시의 실제 값으로 넣지 않는다.

  ```bash
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
    docs/review/2026-08-23-issue-602-health-window-review.md \
    docs/lessons/2026-08-23-issue-602-health-window.md
  git diff --check
  ```

- [ ] **Step 5: review/lesson을 commit한다**

  ```bash
  git add docs/review/2026-08-23-issue-602-health-window-review.md \
    docs/lessons/2026-08-23-issue-602-health-window.md
  git diff --cached --check
  git commit -m "docs: Issue #602 검증 결과와 failure window 교훈을 기록한다"
  ```

## Task 7: DoD 고정과 PR 준비

**Files:**

- Read: `git log`, `git status`, all task commits and changed files
- No source changes unless Task 6 review finding requires a repair

- [ ] **Step 1: DoD evidence matrix를 채운다**

  다음 표를 review/PR body에 같은 값으로 기록한다.

  | DoD | 필요한 증거 |
  |---|---|
  | `S-01` 분류 | window test에서 `BACKEND_ERROR=1`, `CONTENTION=0`, `FAIL_OPEN_FORCED=0` |
  | `S-02` 경계 | fixed/mutable `Clock`로 `failureAt == now-window` 포함, 이전 값 prune |
  | `S-03` bound | capacity eviction/overflow/reset와 view에 이름·예외 없음 |
  | `S-04` 동일 view | readiness detail와 endpoint response의 count/timestamp/window/capacity/overflow 동일 |
  | `S-05` 기존 계약 | status mapping, AOP failure-mode, HTTP path, constructor/copy `javap` 회귀 |
  | `S-06` best-effort | recorder callback/health view 오류가 election/caller/status를 실패시키지 않음 |
  | `S-07` 문서 | EN/KO 설정·lower-bound·non-decision 설명과 terminology audit |

- [ ] **Step 2: worktree와 변경 범위를 확인한다**

  ```bash
  git status --short
  git diff origin/develop...HEAD --stat
  git diff origin/develop...HEAD --check
  git log --oneline --decorate origin/develop..HEAD
  ```

  expected: 변경 파일은 이 계획의 파일 구조와 일치하고 `.workflow-inputs/`는 untracked 상태로 남는다. dirty sibling worktree를 정리하거나 branch를 삭제하지 않는다.

- [ ] **Step 3: PR 생성 전 live metadata를 읽는다**

  구현 완료 뒤에만 `gh issue view 602 --repo bluetape4k/bluetape4k-leader`와 `gh pr checks`/`gh pr view`를 fresh-read한다. PR은 base `develop`, head `feat/epic-spring-s-01-health-window`, Korean title/body, assignee `debop`, Issue #602, Epic #700, milestone `1.0.0`, labels `enhancement`, `feature`, `design`, `integration`, `spring`을 live state와 대조한 뒤 생성한다. PR body 마지막은 `## DoD Status`로 끝낸다.

- [ ] **Step 4: merge는 별도 exact-head gate로 남긴다**

  PR 생성/CI green만으로 merge하지 않는다. merge 직전에 exact head SHA, base, checks, reviews/comments, mergeability, metadata, DoD를 fresh-read하고 사용자의 명시적인 merge 승인을 받은 뒤에만 `--match-head-commit`으로 merge한다. CI가 green이어도 skipped/path-filtered coverage는 별도 gap으로 기록한다.

## Self-review checklist

- [ ] 설계 문서의 모든 포함 요구가 Task 1–6에 매핑되어 있다.
- [ ] 계획 문서에 미완성 작업 표식이나 구현 세부사항을 회피하는 표현이 없다.
- [ ] 모든 code-changing step에 실제 파일, symbol, test name, command, expected output이 있다.
- [ ] `LeaderAcquisitionFailureView`의 field 이름과 모든 task의 사용처가 동일하다.
- [ ] `acquisitionFailureWindow`는 positive finite이고 default `5m`, recorder capacity는 `1024`로 고정되어 있다.
- [ ] 기존 `LeaderObservabilityHealthProperties` 2-인자 constructor/copy와 `LeaderElectionStatusResponse` 단일 인자 constructor/`copy(locks)`를 보존한다.
- [ ] health `Status`는 recent failure만으로 변경되지 않는다.
- [ ] endpoint와 health가 같은 `LeaderAcquisitionFailureWindow` bean을 사용한다.
- [ ] lock name, backend name, exception message, leader identity를 새 window에 저장하지 않는다.
- [ ] `AutoConfiguration.imports`에서 acquisition recorder가 AOP보다 먼저 온다.
- [ ] EN/KO 문서가 같은 property/detail/overflow semantics를 설명한다.
- [ ] 구현 후 focused test, AOP regression, module test, detekt, diff check, ABI, writer audit evidence가 모두 기록된다.

이 계획은 구현 코드를 포함하지 않으며, 사용자 승인 후 `test-driven-development` 순서로 실행한다.
