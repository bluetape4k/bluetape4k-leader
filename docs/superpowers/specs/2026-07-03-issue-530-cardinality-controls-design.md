# Design Spec - Issue #530 Metric Tag Cardinality Controls

## 한국어 해설

이 문서는 `Design Spec - Issue #530 Metric Tag Cardinality Controls`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



- **Issue**: #530 - `feat(leader-micrometer): add metric tag cardinality controls`
- **Date**: 2026-07-03
- **Branch**: `feat/issue-530-cardinality-controls`
- **Worktree**: `.worktrees/feat-issue-530-cardinality-controls`
- **Target modules**: `leader-micrometer`, `leader-spring-boot`, root README locale set

## 1. Problem

`MicrometerLeaderAopMetricsRecorder`, decorator metrics, and `MicrometerLeaderElectionListener`
currently use the raw `lock.name` value as a Micrometer tag. The existing README and KDoc warn
that dynamic SpEL such as tenant-derived lock names can create unbounded meter cardinality, but the
library does not provide first-class controls.

Issue #530 requires configurable allowlist, denylist, hashing, truncation, and redaction for metric
tags, plus safe defaults for high-cardinality lock names and leader IDs. Existing audit/history
records must keep their raw values unless a caller explicitly changes those contracts.

## 2. Current Evidence

| Evidence | Finding |
|---|---|
| `MicrometerLeaderAopMetricsRecorder.kt` | All AOP meters cache meters by raw `lock.name`; registration only logs a WARN on new dynamic names. |
| `InstrumentedLeaderElectors.kt` | Direct decorator metrics also tag raw `lock.name`. |
| `MicrometerLeaderElectionListener.kt` | Listener event counter tags raw `lock.name`. |
| `LeaderObservationOptions.kt` | Observation raw lock names and leader IDs are already opt-in high-cardinality values, disabled by default. |
| `LeaderAopProperties.kt` | Existing Spring namespace is `bluetape4k.leader.aop.metrics.*`; only `enabled` exists today. |
| `README.md` / `README.ko.md` | Docs warn about `lock.name` cardinality and Prometheus `max by (lock_name)` for JVM-local gauges. |
| `docs/superpowers/specs/2026-05-05-leader-micrometer-metrics-design.md` | Original design chose WARN-only cardinality handling; #530 upgrades that warning into a runtime option. |

## 3. Design Risks

| Risk | Mitigation |
|---|---|
| Silent tag drift breaks existing dashboards expecting exact raw `lock.name`. | Make the new default safe for production (`mode=REDACT`), document the migration, and provide explicit raw-compatible opt-out (`mode=RAW`) for callers who accept cardinality risk. |
| Hashing is mistaken for cardinality reduction. | Document hashing as pseudonymization/length normalization only; it still creates one series per raw value and is not the default safe mode. |
| Redaction/collapsing makes pre-registration and deregistration inconsistent. | Apply the same mapper in every recorder path, use exported values as meter cache keys, and make deregistration a raw-input helper that removes only non-shared exported meters. |
| Denylist rules accidentally remove every useful tag value. | Define precedence matrix: denylist wins; allowlist admits exact raw values; redaction is terminal; hash/truncate only apply to non-redacted values. |
| `leader.id` is not currently a low-cardinality meter tag in default metrics. | Reuse the same sanitizer for opt-in Observation `leader.id`; default Observation still does not export leader IDs. |
| Alias tracking reintroduces raw cardinality in memory. | Track raw aliases only for explicit `registerMetricsFor` calls; ordinary callbacks never retain every raw value. |
| Concurrency tests regress into ad hoc `repeat` loops. | Migrate touched in-process stress coverage to `MultithreadingTester`. |

## 4. Approaches

### Approach A - Documentation plus Micrometer `MeterFilter`

Applications would install their own `MeterFilter` to rewrite `lock.name`.

- Pros: no behavior change in this library.
- Cons: repeated boilerplate in every service, easy to forget, and cannot keep recorder cache keys aligned with exported tag values.

**Rejected**: does not satisfy "first-class controls".

### Approach B - Recorder-local tag sanitizer option (chosen)

Add a focused `LeaderMetricTagOptions` / `LeaderMetricTagSanitizer` contract in `leader-micrometer`.
Every meter recorder/listener maps raw tag values through this sanitizer before cache lookup and meter
registration. Spring Boot binds the same options under `bluetape4k.leader.aop.metrics.tags.*` and
injects the sanitizer into the default Micrometer recorder bean.

- Pros: smallest API surface, cache keys match exported tags, no global `MeterRegistry` side effects,
  applies to AOP metrics, decorator metrics, and listener metrics.
- Cons: direct constructor users need to pass options if they want non-default controls.

### Approach C - Global `MeterFilter` auto-configuration

Spring Boot auto-configuration could register a `MeterFilter` that rewrites leader metric tags.

- Pros: one Spring bean can cover user-created meters too.
- Cons: global registry filters are order-sensitive, harder to test, and can mutate unrelated meters
  that happen to use `lock.name`.

**Rejected**: broader blast radius than #530 needs.

## 5. Chosen Design

1. Add serializable public option models in `leader-micrometer` with English KDoc and
   `serialVersionUID`.
   - `LeaderMetricTagOptions`: tag-key aware top-level options.
   - `LeaderMetricTagRule`: per-tag rule with `mode`, `allowList`, `denyList`,
     `hashLength`, `maxLength`, and `redactedValue`.
   - `LeaderMetricTagMode`: `REDACT`, `RAW`, `HASH`, `TRUNCATE`.
2. Defaults:
   - `lock.name`: `mode=REDACT`, `redactedValue="redacted-lock"`.
   - `leader.id`: `mode=REDACT`, `redactedValue="redacted-leader"`.
   - `backend.name`: `mode=RAW`, because backend identifiers are bounded enum-like values.
   - Unknown tag keys: `mode=REDACT`, `redactedValue="redacted"`.
3. Add `LeaderMetricTagSanitizer` that maps `(tagKey, rawValue)` to an exported value.
   - Validate options with bluetape4k `require*` helpers.
   - `redactedValue` must be non-blank.
   - `hashLength` must be in `1..64`.
   - `maxLength` must be `>= 0` globally, but `mode=TRUNCATE` must fail unless
     `maxLength > 0`.
   - Invalid Spring-bound values must fail binding/startup instead of silently normalizing.
   - Precedence:
     | Rule | Result |
     |---|---|
     | `rawValue in denyList` | `redactedValue` |
     | `allowList` non-empty and `rawValue in allowList` | raw value, then optional truncate only if `mode=TRUNCATE` |
     | `allowList` non-empty and `rawValue !in allowList` | `redactedValue` |
     | `mode=REDACT` | `redactedValue` |
     | `mode=HASH` | lowercase SHA-256 hex prefix, length `hashLength` |
     | `mode=TRUNCATE` | raw value truncated to `maxLength`; invalid when `maxLength <= 0` |
     | `mode=RAW` | raw value |
   - Mode-specific lengths are not combined: `mode=HASH` ignores `maxLength`, and
     `mode=TRUNCATE` ignores `hashLength`.
   - Hashing is deterministic unsalted pseudonymization, not anonymization, confidentiality, or
     cardinality reduction. It is dictionary-attackable for tenant, user, job, and other
     low-entropy values. Use redaction for PII, secrets, tenant/user IDs, or other sensitive
     values unless the deployment owns a documented risk model.
   - Allowlist exports matching values raw. Use it only for bounded, non-sensitive, static names;
     Spring allowlist/denylist configuration values are not a secret-hiding mechanism.
   - Use JDK `MessageDigest`; do not add a dependency.
   - Compute the sanitized value exactly once at each public callback/decorator/listener entry and
     pass the exported value to all internal meter lookups for that event.
   - Use a singleton/no-op fast path for `mode=RAW` where possible.
4. Wire sanitizer into:
   - `MicrometerLeaderAopMetricsRecorder`
   - `InstrumentedLeaderElector`
   - `InstrumentedLeaderGroupElector`
   - `InstrumentedSuspendLeaderElector`
   - `MicrometerLeaderElectionListener`
5. Preserve binary/source compatibility:
   - Existing one-argument public JVM constructors remain available as explicit secondary
     constructors or `@JvmOverloads` where appropriate.
   - Those old constructors use the new safe default options; raw-compatible behavior requires
     explicit `LeaderMetricTagOptions.Raw` or Spring `mode=RAW`.
6. Bind Spring properties through `LeaderAopProperties.Metrics.Tags` and inject into
   `LeaderMicrometerAutoConfiguration`.
   - If a user provides a `LeaderMetricTagSanitizer` bean, the default auto-configuration uses
     that bean instead of building one from properties.
   - If a user provides a custom `LeaderAopMetricsRecorder` bean, the current conditional
     behavior still backs off entirely.
   - Spring names:
     - `bluetape4k.leader.aop.metrics.tags.lock-name.mode`
     - `bluetape4k.leader.aop.metrics.tags.lock-name.allow-list`
     - `bluetape4k.leader.aop.metrics.tags.lock-name.deny-list`
     - `bluetape4k.leader.aop.metrics.tags.lock-name.hash-length`
     - `bluetape4k.leader.aop.metrics.tags.lock-name.max-length`
     - `bluetape4k.leader.aop.metrics.tags.lock-name.redacted-value`
     - same shape under `leader-id` and `backend-name`.
   - `backend-name` is supported for bounded backend tags. Current meter paths do not emit
     tenant-derived backend tags; tenant-derived values in this issue flow through `lock.name`
     or opt-in Observation `leader.id`.
7. Reuse the same sanitizer in Observation recorders only when `includeLockName` or
   `includeLeaderId` is enabled. Default Observation behavior remains safe because both are false.
8. Keep audit/history sinks unchanged. This PR only changes exported Micrometer/Observation tag
   values. Audit/history storage remains a restricted trust boundary and must retain raw values
   according to its existing contracts.
9. Use exported values as meter cache keys. `registerMetricsFor(vararg lockNames)` and
   `deregisterMetricsFor(vararg lockNames)` continue to accept raw lock names.
   - `registerMetricsFor` registers the exported value.
   - `deregisterMetricsFor` removes only explicit registrations known to this recorder. In
     collapsing modes (`REDACT`, allowlist miss, denylist hit, or truncation/hash collision), it
     must not remove a shared meter until the last explicit raw registration for that exported
     value is deregistered.
   - Ordinary callback/decorator/listener paths do not retain raw aliases. This preserves the
     cardinality-safety goal in memory as well as in Micrometer. Dynamic meters created only by
     ordinary callbacks may remain until registry lifetime in collapsing modes, which is safer
     than storing unbounded raw alias sets.
   - In-flight active gauges must not go negative or throw if deregistration occurs during a task.

## 6. Default and Migration Contract

| Caller path | Default exported `lock.name` | Raw opt-out |
|---|---|---|
| `MicrometerLeaderAopMetricsRecorder(registry)` | `redacted-lock` | `LeaderMetricTagOptions.Raw` |
| Spring Boot default recorder | `redacted-lock` | `bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW` |
| Decorator wrappers | `redacted-lock` | Pass `tagOptions = LeaderMetricTagOptions.Raw` |
| Observation lock/leader ID | Not exported unless explicitly enabled | If enabled, also sanitized unless rule mode is `RAW` |

This is an intentional behavior change for the default exported tag value. Metric names remain
unchanged. Existing dashboards that group by exact raw lock names must either add an allowlist for
static lock names, use `mode=HASH` with an incident reverse-lookup process, or opt out to raw tags
after accepting the cardinality risk. When values collapse to a redacted/truncated group, Prometheus
queries such as `max by (lock_name) (leader_aop_active)` report the sanitized group, not an exact
logical lock.

Duration dashboards must aggregate timer sums and counts before dividing, for example:

```promql
sum by (lock_name) (rate(leader_aop_execution_duration_seconds_sum[1m]))
/
sum by (lock_name) (rate(leader_aop_execution_duration_seconds_count[1m]))
```

## 7. Acceptance Criteria

- Unit tests prove sanitizer allowlist, denylist, overlap precedence, redaction, truncation, hash
  stability, RAW mode, invalid option validation, `TRUNCATE` with `maxLength <= 0` failure, and
  tag-key-specific defaults.
- Micrometer registry tests prove multiple raw lock names map to one sanitized meter when redacted
  or truncated, and unique hashed values still create one meter per raw value.
- Registration/deregistration tests prove explicit raw registrations do not remove a shared
  exported meter until the last explicit registration is removed; in-flight active gauge
  deregistration stays non-negative.
- Decorator and listener metric tests use the same sanitizer path, including fixed decorator
  `lockName` values after fixed/runtime name selection.
- Spring Boot binding tests prove `bluetape4k.leader.aop.metrics.tags.*` binds and default auto-configuration injects it.
- Spring configuration metadata documents every new property.
- Observation tests prove opt-in lock names and leader IDs are sanitized, while default Observation still exports neither value.
- Touched concurrency stress tests use `MultithreadingTester`, not ad hoc `repeat` / coroutine
  launch loops. Required cases include concurrent first-use of many raw names collapsing to the same
  exported key across AOP recorder, decorator metrics, and listener counters.
- `README.md`, `README.ko.md`, `leader-micrometer/README.md`,
  `leader-micrometer/README.ko.md`, `leader-spring-boot/README.md`, and
  `leader-spring-boot/README.ko.md` document production presets, raw opt-out, direct constructor
  examples, corrected Prometheus duration queries, collapsed-tag semantics, and the raw audit/history boundary.
- Public KDoc for the new option/sanitizer contract is English.
- Static checks confirm sanitizer usage stays inside Micrometer/Observation export paths and does
  not alter audit/history storage contracts.

## 8. Out of Scope

- Changing audit/history storage contracts.
- Adding a global Spring `MeterFilter`.
- Changing metric names.
- Enabling raw Observation lock names or leader IDs by default.
- Adding new dependencies.

## 9. DoD

| Item | Status |
|---|---|
| Current source behavior cited | Done |
| 3 approaches compared | Done |
| Chosen approach minimizes blast radius | Done |
| Tests and README locale updates included | Done |
| `$bluetape4k-code-patterns` constraints included | Done |
