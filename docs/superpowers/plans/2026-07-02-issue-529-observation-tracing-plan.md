# Issue #529 Observation Tracing Implementation Plan

## 한국어 해설

이 문서는 `Issue #529 Observation Tracing Implementation Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add optional Micrometer Observation tracing for leader AOP execution, with Spring Boot auto-configuration, example code, and README coverage.

**Architecture:** `leader-micrometer` owns the framework-neutral `ObservationRegistry` recorder. `leader-spring-boot` wires it when the registry is present and binds safe tracing properties. `examples/prometheus-dashboard` demonstrates the user-facing setup without adding an OpenTelemetry SDK/exporter.

**Tech Stack:** Kotlin 2.4.0, Micrometer 1.16 Observation API, Spring Boot 4 auto-configuration, JUnit 5, bluetape4k assertions, `MultithreadingTester`.

---

## Files

- Modify: `gradle/libs.versions.toml`
- Modify: `leader-micrometer/build.gradle.kts`
- Create: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/LeaderObservationOptions.kt`
- Create: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderAopMetricsRecorder.kt`
- Create: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderElectionListener.kt`
- Modify: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt`
- Create: `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderAopMetricsRecorderTest.kt`
- Create: `leader-micrometer/src/test/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderElectionListenerTest.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderObservabilityProperties.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderMicrometerAutoConfiguration.kt`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfigurationTest.kt`
- Modify: `examples/prometheus-dashboard/src/main/kotlin/io/bluetape4k/leader/examples/prometheus/PrometheusDashboardApp.kt`
- Modify: `examples/prometheus-dashboard/src/main/resources/application.yml`
- Modify: `leader-micrometer/README.md`
- Modify: `leader-micrometer/README.ko.md`
- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Modify: `examples/prometheus-dashboard/README.md`
- Modify: `examples/prometheus-dashboard/README.ko.md`
- Create: `docs/review/2026-07-02-issue-529-observation-tracing-review.md`
- Create: `docs/lessons/2026-07-02-issue-529-observation-tracing.md`

## Task 1: Observation Recorder RED Tests

- [ ] Create `MicrometerObservationLeaderAopMetricsRecorderTest.kt`.
- [ ] Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`.
- [ ] Use class-level `ObservationRegistry`, collecting handler, recorder, and options fields; reset in `@BeforeEach`.
- [ ] Add a local collecting `ObservationHandler<Observation.Context>` that captures `onStart`, `onStop`, `onError`, and context key values.
- [ ] Write failing tests for:
  - `onLockAcquired` emits one `leader.aop.acquire` observation with outcome `acquired` and high-cardinality `acquire.elapsed.ms`;
  - `onLockNotAcquired(BACKEND_ERROR)` emits outcome `skipped` and reason `BACKEND_ERROR`;
  - `onTaskFinished` emits `leader.aop.execution` outcome `success` and high-cardinality `execution.elapsed.ms`;
  - `onTaskFailed(IllegalStateException)` emits outcome `error` and exception simple class name without raw throwable export by default;
  - `LeaderObservationOptions(includeExceptionDetails = true)` calls `Observation.error(...)` for non-cancellation failures;
  - `onTaskStarted` then `onTaskFailed(CancellationException)` emits outcome `cancelled` and does not call `Observation.error`;
  - lock name and leader id are absent by default;
  - lock name and leader id are present as high-cardinality key values when `LeaderObservationOptions(includeLockName = true, includeLeaderId = true)`;
  - context-bearing overloads with `LeaderAopMetricsContext.Identified` emit `leader.id` and `leader.id.source` only when `includeLeaderId=true`;
  - context-bearing overloads with `LeaderAopMetricsContext.Unknown` never emit `leader.id`;
  - backend failure sequence `onLockAttempt -> onLockNotAcquired(BACKEND_ERROR) -> onTaskFailed` emits a skipped acquire observation and a standalone execution error observation;
  - `ObservationRegistry.NOOP` does not emit handler callbacks;
  - recorder observations do not become the current `ObservationRegistry.currentObservation` around a user body because #529 emits standalone terminal observations only;
  - same-lock concurrent terminal callbacks use `MultithreadingTester().workers(4).rounds(25)` and complete without race failures.
- [ ] Run:

```bash
./gradlew :bluetape4k-leader-micrometer:test --tests '*MicrometerObservationLeaderAopMetricsRecorderTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL because `MicrometerObservationLeaderAopMetricsRecorder` and `LeaderObservationOptions` do not exist.

## Task 2: Implement Observation Recorder

- [ ] Add `micrometer-observation = { module = "io.micrometer:micrometer-observation" }` to `gradle/libs.versions.toml`.
- [ ] Add `api(libs.micrometer.observation)` to `leader-micrometer/build.gradle.kts` because `ObservationRegistry` is public API.
- [ ] Add `LeaderObservationOptions` as a public serializable data class with English KDoc, safe defaults, and `serialVersionUID`.
- [ ] Add public top-level observation constants, keeping `MicrometerNames` internal:
  - `OBSERVATION_LEADER_AOP_ACQUIRE = "leader.aop.acquire"`
  - `OBSERVATION_LEADER_AOP_EXECUTION = "leader.aop.execution"`
  - `OBSERVATION_LEADER_ELECTION_EVENT = "leader.election.event"`
  - `OBSERVATION_TAG_OPERATION = "leader.operation"`
  - `OBSERVATION_TAG_OUTCOME = "outcome"`
  - `OBSERVATION_TAG_REASON = "reason"`
  - `OBSERVATION_TAG_EXCEPTION = "exception"`
  - `OBSERVATION_TAG_EVENT = "event"`
  - `OBSERVATION_TAG_ACQUIRE_ELAPSED_MS = "acquire.elapsed.ms"`
  - `OBSERVATION_TAG_EXECUTION_ELAPSED_MS = "execution.elapsed.ms"`
- [ ] Add `MicrometerObservationLeaderAopMetricsRecorder`.
- [ ] Keep `onLockAttempt` and `onTaskStarted` as no-ops in this recorder because the SPI has no invocation id.
- [ ] On terminal callbacks, create a short observation, add bounded low-cardinality attributes plus numeric elapsed values as high-cardinality attributes, optionally record non-cancellation raw errors only when `includeExceptionDetails=true`, start, and stop immediately.
- [ ] Return immediately when `ObservationRegistry.isNoop()` is true.
- [ ] Include lock name and leader id only as high-cardinality key values when options enable them and context is identified.
- [ ] Run `./gradlew :bluetape4k-leader-micrometer:compileKotlin --no-daemon --no-configuration-cache --console=plain` after the dependency and public API changes.
- [ ] Rerun the Task 1 Gradle command.

Expected: PASS.

## Task 3: Listener Event Observation

- [ ] Write `MicrometerObservationLeaderElectionListenerTest.kt` first.
- [ ] Cover `onElected`, `onRevoked`, and `onSkipped` with `leader.election.event`, low-cardinality `event`, and optional high-cardinality `lock.name`.
- [ ] Run:

```bash
./gradlew :bluetape4k-leader-micrometer:test --tests '*MicrometerObservationLeaderElectionListenerTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL before implementation, PASS after `MicrometerObservationLeaderElectionListener` is added.

## Task 4: Spring Boot Auto-Configuration RED Tests

- [ ] Create `LeaderObservationAutoConfigurationTest.kt`.
- [ ] Use `ApplicationContextRunner` with `LeaderMicrometerAutoConfiguration`, `LeaderObservationAutoConfiguration`, and `LeaderAopAutoConfiguration`.
- [ ] Add test configurations for `ObservationRegistry`, `SimpleMeterRegistry`, custom observation recorder, and custom generic recorder.
- [ ] Write failing tests for:
  - observation recorder auto-registers when `ObservationRegistry` exists;
  - no recorder is registered without `ObservationRegistry`;
  - `bluetape4k.leader.observability.enabled=false` disables observation recorder and listener beans even if `bluetape4k.leader.observability.tracing.enabled=true`;
  - `bluetape4k.leader.observability.tracing.enabled=false` disables it;
  - `include-lock-name=true` and `include-leader-id=true` bind into options;
  - `include-exception-details=true` binds into options;
  - meter and observation recorders coexist when both registries exist;
  - user-supplied `MicrometerObservationLeaderAopMetricsRecorder` plus `MeterRegistry` still preserves default `MicrometerLeaderAopMetricsRecorder`;
  - unrelated custom generic `LeaderAopMetricsRecorder` still suppresses the default meter recorder;
  - custom `MicrometerObservationLeaderAopMetricsRecorder` wins over auto-config;
  - `MicrometerObservationLeaderElectionListener` appears when tracing is enabled;
  - `AutoConfiguration.imports` keeps `LeaderObservationAutoConfiguration` after `LeaderMicrometerAutoConfiguration` and before `LeaderAopAutoConfiguration`.
- [ ] Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL because auto-configuration and properties do not exist.

## Task 5: Implement Spring Boot Auto-Configuration

- [ ] Extend `LeaderObservabilityProperties` with serializable `LeaderTracingProperties` and `serialVersionUID`.
- [ ] Add `LeaderObservationAutoConfiguration` with class, bean, property, missing-bean, and infrastructure role guards.
- [ ] Add `@EnableConfigurationProperties(LeaderProperties::class)` to `LeaderObservationAutoConfiguration`.
- [ ] Map properties into `LeaderObservationOptions`.
- [ ] Register `MicrometerObservationLeaderElectionListener` under the same tracing property so the existing observability registrar can attach it to listener-aware electors.
- [ ] Adjust `LeaderMicrometerAutoConfiguration` so a concrete observation recorder does not suppress the default meter recorder, while an unrelated custom `LeaderAopMetricsRecorder` still does.
- [ ] Do not modify `LeaderElectionAspect` or `LeaderGroupElectionAspect` to synthesize `leader.id`. Current Spring AOP annotations do not expose a real leader identity; `include-leader-id=true` is supported only for direct/context-aware recorder calls until a follow-up identity-aware AOP contract exists.
- [ ] Register the new auto-configuration after `LeaderMicrometerAutoConfiguration` and before `LeaderAopAutoConfiguration`.
- [ ] Update `additional-spring-configuration-metadata.json` for `observability.tracing.enabled`, `include-lock-name`, `include-leader-id`, and `include-exception-details`.
- [ ] Add a source-level assertion or test that `AutoConfiguration.imports` orders `LeaderObservationAutoConfiguration` after `LeaderMicrometerAutoConfiguration` and before `LeaderAopAutoConfiguration`.
- [ ] Rerun the Task 4 Gradle command.

Expected: PASS.

## Task 6: Example Code

- [ ] Update `PrometheusDashboardApp.kt` with a small example configuration component that observes leader tracing through `ObservationRegistry`.
- [ ] Keep it optional and non-exporter-specific. Do not add OpenTelemetry SDK/exporter dependencies.
- [ ] Update `application.yml` with:

```yaml
bluetape4k:
  leader:
    observability:
      tracing:
        enabled: true
        include-lock-name: false
        include-leader-id: false
        include-exception-details: false
```

- [ ] Add a focused example test only if the example code has behavior that is not already covered by Spring Boot auto-configuration tests.
- [ ] Run:

```bash
./gradlew :examples:prometheus-dashboard:compileKotlin :examples:prometheus-dashboard:compileTestKotlin --no-daemon --no-configuration-cache --console=plain
```

Expected: PASS.

## Task 7: README Updates

- [ ] Update `leader-micrometer/README.md` with direct Observation recorder setup, attribute table, cancellation behavior, and cardinality notes.
- [ ] Update `leader-micrometer/README.ko.md` with equivalent Korean content.
- [ ] Update `leader-spring-boot/README.md` with auto-configuration conditions and properties.
- [ ] Update `leader-spring-boot/README.ko.md` with equivalent Korean content.
- [ ] Update `examples/prometheus-dashboard/README.md` and `README.ko.md` with the tracing example and what is intentionally not included, including the fact that lease-extension observations require a future core event contract.
- [ ] README text must say raw lock names and leader IDs may contain tenant/user/job identifiers, are not redacted by #529, and require application-level naming hygiene or filtering.
- [ ] README text must say raw exception details are disabled by default because exporters can include messages and stack traces.
- [ ] README text must include rollback snippets:
  - disable all leader observability with `bluetape4k.leader.observability.enabled=false`;
  - disable only tracing with `bluetape4k.leader.observability.tracing.enabled=false`;
  - keep metrics on with `bluetape4k.leader.aop.metrics.enabled=true`.
- [ ] README text must include a property table covering `aop.metrics.enabled`, `observability.enabled`, `observability.tracing.enabled`, `include-lock-name`, `include-leader-id`, and `include-exception-details`, including defaults and migration notes.
- [ ] README text must state observations are standalone terminal observations and do not make the leader body run under a new current `Observation.Scope`.
- [ ] README text must state `include-leader-id=true` emits `leader.id` only when the recorder receives `LeaderAopMetricsContext.Identified`; current Spring AOP does not invent a leader ID from node IDs or lock names.
- [ ] README text must state #529 emits Micrometer Observations only. Applications must add their own Micrometer tracing bridge, exporter, collector, and OpenTelemetry SDK if they want exported traces.
- [ ] README text must state the example `ObservationHandler` is a local demo hook, not production export configuration.
- [ ] README text must include a note that lease-extension Observation is deferred to follow-up issue #559.
- [ ] Verify public docs use actual source names:

```bash
rg "MicrometerObservationLeaderAopMetricsRecorder|LeaderObservationOptions|observability.tracing" leader-micrometer leader-spring-boot examples/prometheus-dashboard
```

Expected: new names appear only in implementation, tests, and README entries.

## Task 8: Full Targeted Verification

- [ ] Run:

```bash
./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test :examples:prometheus-dashboard:test --no-daemon --no-configuration-cache --console=plain
```

Expected: PASS.

- [ ] Verify no tracing exporter dependency was introduced:

```bash
./gradlew :bluetape4k-leader-micrometer:dependencies --configuration runtimeClasspath --no-daemon --no-configuration-cache --console=plain | rg "opentelemetry-|micrometer-tracing-bridge|exporter|collector" || true
./gradlew :bluetape4k-leader-spring-boot:dependencies --configuration runtimeClasspath --no-daemon --no-configuration-cache --console=plain | rg "opentelemetry-|micrometer-tracing-bridge|exporter|collector" || true
```

Expected: no new OpenTelemetry SDK, tracing bridge, exporter, or collector dependency from this issue.

- [ ] Run:

```bash
git diff --check
```

Expected: no whitespace errors.

- [ ] Run CodeGraph review context against the diff.
- [ ] Run local 7-tier code review and write `docs/review/2026-07-02-issue-529-observation-tracing-review.md`.
- [ ] Fix any P0/P1 findings and rerun affected tests.

## Task 9: Lesson, Commit, PR

- [ ] Add `docs/lessons/2026-07-02-issue-529-observation-tracing.md` with context, decision, outcome, verification, and future guard.
- [x] Create or update a follow-up GitHub issue for `LockExtender` lease-extension Observation, assign `debop`, label it consistently with #529, and link it from the PR body and README note. Created #559.
- [ ] Commit only related files with a Lore-format English commit message.
- [ ] Push the branch.
- [ ] Open PR linked to #529, assign `debop`, copy issue labels and milestone, and make the final PR body section `## DoD Status`.
- [ ] Verify live PR metadata and body:

```bash
gh pr view --json number,title,body,labels,milestone,assignees,state,url
```

Expected: assignee `debop`, labels `enhancement`, `feature`, `integration`, milestone `Backlog`, final body section `## DoD Status`.

## Stop Conditions

- Stop as complete only after tests, review artifact, lesson, commit, push, PR creation, live PR metadata verification, and CI evidence.
- Stop as blocked if Micrometer Observation classes are unavailable from the current dependency graph and adding `micrometer-observation` cannot be done without changing the central catalog.
