# Issue #529 Observation Tracing Design

## 한국어 해설

이 문서는 `Issue #529 Observation Tracing Design`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Context

Issue #529 asks for a Micrometer Observation / OpenTelemetry tracing bridge for
leader election. The bridge must complement existing Micrometer meters instead
of replacing counters, timers, gauges, or listener events.

The user approved the design scope with one extra requirement: this work must
include example-style code and detailed README coverage, not only tests.

Relevant current code:

- `leader-core` already exposes `LeaderAopMetricsRecorder` with context-bearing
  callbacks and documented call order.
- `leader-micrometer` already provides `MicrometerLeaderAopMetricsRecorder` for
  meter recording.
- `leader-spring-boot` auto-registers the meter recorder when `MeterRegistry`
  is present.
- `examples/prometheus-dashboard` is the existing user-facing observability
  example and is the natural place to demonstrate tracing configuration.

## Goals

1. Add an optional Observation recorder in `leader-micrometer`.
2. Support Spring Boot auto-configuration when `ObservationRegistry` is present.
3. Keep trace attributes low-cardinality by default.
4. Add opt-in properties for lock-name and leader-id detail.
5. Preserve existing metrics recorder behavior and allow meter + observation
   recorders to run together.
6. Record listener lifecycle observations for elected, revoked, and skipped
   events where listener-aware electors expose them.
7. Add example code in `examples/prometheus-dashboard`.
8. Update `README.md` and `README.ko.md` for `leader-micrometer`,
   `leader-spring-boot`, and the example where needed.

## Non-Goals

- Do not add an OpenTelemetry SDK, exporter, collector, or global tracing
  configuration. Applications choose their exporter.
- Do not replace existing meter names or existing `lock.name` meter tags.
- Do not implement full tag cardinality policy such as allowlist, denylist,
  hashing, truncation, or redaction. That belongs to issue #530.
- Do not create a second event pipeline. Reuse AOP metrics callbacks and
  `LeaderAopMetricsContext`.
- Do not synthesize `leader.id` from Spring node IDs, lock names, or other
  non-leader identity values. In the current Spring AOP path,
  `@LeaderElection`/`@LeaderGroupElection` do not expose a real leader-id
  resolution contract. #529 supports identified contexts when callers provide
  them; automatic Spring AOP leader-id population needs a follow-up identity
  contract.
- Do not add lease-extension events in this issue. `LockExtender` has detailed
  extend APIs, but no metrics/observation hook is exposed. Instrumenting those
  calls would be a separate core contract change spanning blocking and suspend
  extender paths, not a `leader-micrometer` bridge-only change.
- Follow-up GitHub issue #559 tracks lease-extension Observation. Keep #529
  scoped to acquire, task, skipped/backend-error, and revoke/listener events.
- Do not change distributed lock semantics or backend implementations.

## Observation Recorder Design

Add `MicrometerObservationLeaderAopMetricsRecorder` in `leader-micrometer`.
It implements `LeaderAopMetricsRecorder` and records bounded Observations for
the AOP path.

The recorder uses Micrometer's `ObservationRegistry`; it does not depend on an
OpenTelemetry SDK. A configured Micrometer tracing bridge can later translate
Observations to OTel spans.

`leader-micrometer` must add a direct `api(libs.micrometer.observation)`
dependency and a `micrometer-observation` version-catalog alias. The
Observation recorder constructor exposes `ObservationRegistry` in public API, so
the module must not rely on a transitive Micrometer core dependency for this
type.

Also add `MicrometerObservationLeaderElectionListener` for framework-neutral
listener events. It implements `LeaderElectionListener` and emits short
Observations for `elected`, `revoked`, and `skipped` lifecycle callbacks. This
covers the revoke part of #529 without creating a second event model.

### Observation lifecycle

Do not maintain started observations across callbacks in this issue. The current
`LeaderAopMetricsRecorder` SPI has no invocation id, and stop callbacks carry
only the lock name. A per-lock queue cannot correctly pair `start A, start B,
stop B, stop A` under same-lock concurrency. Adding an invocation id would be a
core SPI change and is out of scope for #529.

Instead, the recorder emits short bounded observations at terminal callbacks:

| Callback | Observation | Outcome | Timing model |
|---|---|---|---|
| `onLockAcquired` | `leader.aop.acquire` | `acquired` | `acquire.elapsed.ms` from the callback duration |
| `onLockNotAcquired` | `leader.aop.acquire` | `skipped` | no span duration claim |
| `onTaskFinished` | `leader.aop.execution` | `success` | `execution.elapsed.ms` from the callback duration |
| `onTaskFailed` | `leader.aop.execution` | `error` or `cancelled` | `execution.elapsed.ms` from the callback duration |

`onLockAttempt` and `onTaskStarted` are no-ops for this recorder. This avoids
leaks and mis-paired spans while preserving the existing AOP timing semantics:
`executionTime` is attempt-to-completion elapsed time, matching the existing
`leader.aop.execution.duration` meter contract.

The observation's actual start/stop duration is intentionally short. Consumers
should treat `*.elapsed.ms` as the measured leader-election elapsed value and
the observation as a tracing/correlation event, not as a long-lived same-lock
span. A future core issue can add per-invocation identity if true long-lived
spans are required.

These observations are standalone and do not open a current `Observation.Scope`
around the guarded method body. Downstream spans/log correlation therefore
continue to use the application's existing current observation. Current-scope
propagation would require an API that can keep a scope open from
`onTaskStarted` to every terminal path without same-lock mis-pairing; that is
deferred with the per-invocation SPI change.

### Observation names and tags

Use stable observation names:

- `leader.aop.acquire`
- `leader.aop.execution`
- `leader.election.event`

Expose these names and shared tag keys as public top-level constants in
`leader-micrometer` so documentation and downstream tests do not depend on
`internal object MicrometerNames`. At minimum:

- `OBSERVATION_LEADER_AOP_ACQUIRE`
- `OBSERVATION_LEADER_AOP_EXECUTION`
- `OBSERVATION_LEADER_ELECTION_EVENT`
- `OBSERVATION_TAG_OPERATION`
- `OBSERVATION_TAG_OUTCOME`
- `OBSERVATION_TAG_REASON`
- `OBSERVATION_TAG_EXCEPTION`
- `OBSERVATION_TAG_EVENT`
- `OBSERVATION_TAG_ACQUIRE_ELAPSED_MS`
- `OBSERVATION_TAG_EXECUTION_ELAPSED_MS`

Use low-cardinality attributes by default:

- `leader.operation`: `acquire` or `execute`
- `outcome`: `attempted`, `acquired`, `skipped`, `success`, `error`,
  `cancelled`
- `reason`: only for skipped acquire paths, using `SkipReason.name`
- `exception`: exception simple class name for non-cancellation failures
- `leader.id.source`: only when `LeaderAopMetricsContext.Identified` is present
- `event`: only for listener event observations, using `elected`, `revoked`,
  or `skipped`

Use high-cardinality attributes for diagnostic values and raw identifiers:

- `acquire.elapsed.ms`: callback-provided acquire elapsed time in milliseconds
  for acquired paths
- `execution.elapsed.ms`: callback-provided attempt-to-completion elapsed time
  in milliseconds for task terminal paths
- `lock.name`
- `leader.id`

Cancellation must not be recorded as an error. `CancellationException` receives
outcome `cancelled` and stops the observation without `Observation.error(...)`.

By default, non-cancellation failures must not pass the raw `Throwable` to
`Observation.error(...)`, because OTel bridges may export exception messages or
stack traces containing tenant IDs, lock names, URLs, or credentials. Record
only `outcome=error` and the exception simple class name by default.

### Options

Add a small serializable options value:

```kotlin
data class LeaderObservationOptions(
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

The primary recorder constructor accepts:

```kotlin
class MicrometerObservationLeaderAopMetricsRecorder(
    private val registry: ObservationRegistry,
    val options: LeaderObservationOptions = LeaderObservationOptions(),
) : LeaderAopMetricsRecorder
```

This keeps safe defaults while allowing direct users and Spring Boot properties
to opt into richer trace detail. `includeExceptionDetails` is intentionally
separate from `includeLeaderId`; enabling it can export raw exception details
through tracing bridges and must be documented as production-sensitive.

### Hot-path budget

When `ObservationRegistry.isNoop()` is true, every recorder callback returns
without creating an `Observation`. With a real registry, this feature allocates
one short observation only on terminal callbacks and does not allocate per-lock
queues. Tests cover the no-op fast path and a `MultithreadingTester` stress path
so observation fan-out remains race-free under contention.

The accepted overhead budget for #529 is qualitative and testable:

- no allocation on `onLockAttempt` or `onTaskStarted`;
- no per-lock map/deque state;
- at most one short `Observation` allocation per terminal callback when the
  registry is not no-op;
- no additional backend calls, threads, coroutine jobs, or exporters.

## Spring Boot Design

Add `LeaderObservationAutoConfiguration` in `leader-spring-boot`.

Conditions:

- `@ConditionalOnClass(name = ["io.micrometer.observation.ObservationRegistry",
  "io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder"])`
- `@ConditionalOnBean(ObservationRegistry::class)`
- `@ConditionalOnProperty(prefix = "bluetape4k.leader.observability",
  name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@ConditionalOnProperty(prefix = "bluetape4k.leader.observability.tracing",
  name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@ConditionalOnMissingBean(MicrometerObservationLeaderAopMetricsRecorder::class)`

Ordering:

- after `LeaderMicrometerAutoConfiguration`
- before `LeaderAopAutoConfiguration`
- register in `AutoConfiguration.imports`

Extend `LeaderObservabilityProperties` with nested tracing options:

```kotlin
val tracing: LeaderTracingProperties = LeaderTracingProperties()
```

and:

```kotlin
data class LeaderTracingProperties(
    val enabled: Boolean = true,
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
```

`LeaderObservationAutoConfiguration` must bind `LeaderProperties`
independently with `@EnableConfigurationProperties(LeaderProperties::class)`.
It must not assume another auto-configuration imported earlier has already
registered configuration properties binding.

Update `META-INF/spring/additional-spring-configuration-metadata.json` for all
new tracing properties:

- `bluetape4k.leader.observability.tracing.enabled`
- `bluetape4k.leader.observability.tracing.include-lock-name`
- `bluetape4k.leader.observability.tracing.include-leader-id`
- `bluetape4k.leader.observability.tracing.include-exception-details`

The existing meter auto-configuration should remain behavior-compatible:
a custom generic `LeaderAopMetricsRecorder` still suppresses the default meter
recorder, except that `MicrometerObservationLeaderAopMetricsRecorder` alone must
not suppress `MicrometerLeaderAopMetricsRecorder`. This preserves meter +
observation coexistence when a user supplies only a custom observation recorder.
The implementation may use a small custom condition for the meter recorder:
register the default meter recorder when no concrete
`MicrometerLeaderAopMetricsRecorder` exists and no non-observation
`LeaderAopMetricsRecorder` exists.

When listener observability is enabled, the same auto-configuration also
registers `MicrometerObservationLeaderElectionListener` as a
`LeaderElectionListener` if no concrete listener bean of that type exists. The
existing `LeaderElectionObservabilityAutoConfiguration` registrar attaches
listener beans to listener-aware elector registries.

The Observation recorder implements the context-bearing
`LeaderAopMetricsRecorder` overloads so direct callers and future AOP paths can
pass `LeaderAopMetricsContext.Identified`. The current Spring AOP annotations do
not carry a real leader identity, and the current lock-name based AOP path must
continue to pass through the existing legacy callback behavior. #529 therefore
does not modify the AOP aspects to fabricate `leader.id`; README files must
state that `include-leader-id=true` emits values only when an identified context
is provided by the caller or by a future slot-aware/identity-aware AOP path.

Default-on behavior is acceptable only because safe defaults avoid raw lock
names, raw leader IDs, and raw exception detail. README files must still state
that an already configured tracing bridge/exporter can receive these new leader
observations when an `ObservationRegistry` exists.

Rollback precedence:

1. `bluetape4k.leader.observability.enabled=false` disables tracing and listener
   observation beans together with other observability support.
2. `bluetape4k.leader.observability.tracing.enabled=false` disables only the
   Observation tracing bridge.
3. `bluetape4k.leader.aop.metrics.enabled=false` remains limited to the meter
   recorder and does not control Observation tracing.

Property table:

| Property | Default | Controls | Migration note |
|---|---:|---|---|
| `bluetape4k.leader.aop.metrics.enabled` | `true` | Existing Micrometer meter recorder | Existing configs keep meter behavior. |
| `bluetape4k.leader.observability.enabled` | `true` | Status/event observability and tracing parent switch | Set to `false` for an emergency all-observability rollback. |
| `bluetape4k.leader.observability.tracing.enabled` | `true` | Observation recorder and listener bridge | Set to `false` to disable tracing while keeping metrics/status. |
| `bluetape4k.leader.observability.tracing.include-lock-name` | `false` | Raw `lock.name` high-cardinality attribute | Raw value is not redacted by #529. |
| `bluetape4k.leader.observability.tracing.include-leader-id` | `false` | Raw `leader.id` high-cardinality attribute | Raw value is not redacted by #529. |
| `bluetape4k.leader.observability.tracing.include-exception-details` | `false` | Raw throwable export through `Observation.error(...)` | Can export message/stack trace through tracing bridges. |

## Example and README Design

Update `examples/prometheus-dashboard`:

- add example code showing how the tracing auto-configuration is activated by
  an `ObservationRegistry` and how a local `ObservationHandler` can observe
  leader observations;
- add YAML showing safe defaults and optional lock-name / leader-id detail;
- update README files to explain that metrics remain Prometheus-oriented while
  Observation is the bridge to tracing exporters.
- state that the local `ObservationHandler` is demo-only. Production
  OpenTelemetry export requires the application to add its own Micrometer
  tracing bridge/exporter/collector dependencies and configuration.

Update module README files:

- `leader-micrometer/README.md` and `README.ko.md`: direct construction example,
  observation names, low/high-cardinality attributes, cancellation behavior,
  standalone scope behavior, and no current-scope propagation.
- `leader-spring-boot/README.md` and `README.ko.md`: auto-configuration
  conditions, properties, rollback snippets, and how to confirm metrics still
  exist when tracing is disabled.
- all README files must explicitly state that #529 emits Micrometer
  Observations only and does not add OpenTelemetry SDK, exporter, collector, or
  Micrometer tracing bridge dependencies.

## Test Plan

Use TDD:

1. Write `MicrometerObservationLeaderAopMetricsRecorderTest`.
   - `onLockAcquired` emits one `leader.aop.acquire` observation with
     `outcome=acquired` and high-cardinality `acquire.elapsed.ms`;
   - `onLockNotAcquired(BACKEND_ERROR)` emits `outcome=skipped` and
     `reason=BACKEND_ERROR`;
   - `onTaskFinished` emits `leader.aop.execution`, `outcome=success`, and
     high-cardinality `execution.elapsed.ms`;
   - `onTaskFailed(IllegalStateException)` emits `outcome=error` and exception
     simple class name without raw throwable export by default;
   - `includeExceptionDetails=true` records the raw throwable through
     `Observation.error(...)`;
   - `CancellationException` records outcome `cancelled` without error;
   - lock name and leader id are absent by default and present as high
     cardinality attributes only when enabled;
   - an explicit `LeaderAopMetricsContext.Identified` produces `leader.id` and
     `leader.id.source` only when `includeLeaderId=true`;
   - backend failure sequence
     `onLockAttempt -> onLockNotAcquired(BACKEND_ERROR) -> onTaskFailed`
     produces one skipped acquire observation and one standalone execution error
     observation;
   - `ObservationRegistry.NOOP` produces no handler callbacks;
   - concurrent same-lock terminal callbacks use `MultithreadingTester`, not
     ad hoc `repeat`/thread loops.
2. Write `MicrometerObservationLeaderElectionListenerTest`.
   - `onElected`, `onRevoked`, and `onSkipped` emit `leader.election.event`
     observations with bounded event values;
   - lock name is high-cardinality only when enabled.
3. Write `LeaderObservationAutoConfigurationTest`.
   - recorder appears when `ObservationRegistry` exists;
   - recorder is disabled by property;
   - lock-name/leader-id options bind from properties;
   - meter recorder and observation recorder coexist when `MeterRegistry` and
     `ObservationRegistry` are both present;
   - user-supplied observation recorder plus `MeterRegistry` still preserves the
     default meter recorder;
   - parent kill switch `bluetape4k.leader.observability.enabled=false`
     disables tracing even when `observability.tracing.enabled=true`;
   - user-supplied observation recorder wins;
   - listener observation bean appears when listener tracing is enabled;
   - `LeaderObservationAutoConfiguration` appears in imports after metrics and
     before AOP so recorder beans are available before the aspect
     auto-configuration phase.
4. Add or update example tests only if the example code has executable behavior
   beyond static Spring configuration.
5. Run targeted Gradle commands for `leader-micrometer`,
   `leader-spring-boot`, and `examples:prometheus-dashboard`.
6. Confirm follow-up issue #559 for `LockExtender` lease-extension Observation
   and link it from README notes.

## Risks

- The AOP SPI has no invocation id. #529 avoids cross-callback pairing and emits
  terminal observations instead. True long-lived spans require a future SPI
  change.
- Adding `ObservationRegistry` method signatures in Spring auto-configuration
  requires class guards so optional Micrometer classes do not break consumers.
- High-cardinality trace attributes can be expensive. They remain disabled by
  default and are documented as opt-in production risk.
- Raw exception details can leak sensitive data through exporters. They remain
  disabled by default and require explicit opt-in.

## Acceptance Criteria

- Existing meter recorder tests still pass.
- New observation recorder tests pass without AssertJ/JUnit assertion APIs.
- Spring Boot auto-configuration tests prove conditional behavior.
- Spring Boot auto-configuration tests prove recorder/listener registration,
  property binding, coexistence with the meter recorder, and import order before
  the AOP phase.
- Auto-configuration tests prove parent and child disable properties.
- Auto-configuration tests prove a user-supplied observation recorder does not
  accidentally disable the default meter recorder.
- `examples/prometheus-dashboard` contains source-level example code for
  tracing/Observation.
- English and Korean README files explain direct and Spring Boot usage.
- No OpenTelemetry exporter or SDK dependency is introduced by this issue.
- Revoke observations are supported through listener events; lease-extension
  observations are explicitly documented as out of scope until a core extend
  event contract exists, with linked follow-up issue #559.
