# Implementation Plan - Issue #530 Metric Tag Cardinality Controls

- **Issue**: #530 - `feat(leader-micrometer): add metric tag cardinality controls`
- **Date**: 2026-07-03
- **Spec**: `docs/superpowers/specs/2026-07-03-issue-530-cardinality-controls-design.md`
- **Branch**: `feat/issue-530-cardinality-controls`

## Success Criteria

Implement first-class metric tag cardinality controls for leader Micrometer metrics and opt-in
Observation high-cardinality values. The implementation must default to safe redacted lock names,
preserve metric names and public constructor availability, support Spring binding, update EN/KO docs,
and verify behavior with targeted tests that use bluetape4k test patterns.

## Work Plan

| Step | Action | Expected DoD |
|---|---|---|
| 1 | Add failing sanitizer unit tests in `leader-micrometer` for mode defaults, allowlist/denylist precedence, redaction, hashing, truncation, raw mode, validation, and tag-key fallback. | Tests fail because `LeaderMetricTagOptions` / `LeaderMetricTagSanitizer` do not exist. |
| 2 | Implement `LeaderMetricTagMode`, `LeaderMetricTagRule`, `LeaderMetricTagOptions`, and `LeaderMetricTagSanitizer` in `leader-micrometer` with English KDoc, `Serializable` data classes, `serialVersionUID`, bluetape4k `require*` validation, and JDK SHA-256 hashing. | Sanitizer tests pass; `mode=HASH` ignores `maxLength`, `mode=TRUNCATE` fails when `maxLength <= 0` and ignores `hashLength`, and hashing is deterministic lowercase hex. Rules are immutable after construction, allow/deny lists are sets, `REDACT`/`RAW` avoid per-call collection/regex/string-builder allocation, no unbounded raw-value hash cache is introduced, and hash uses no shared non-thread-safe `MessageDigest`. |
| 3 | Add failing registry tests for `MicrometerLeaderAopMetricsRecorder`: redacted default collapses multiple raw names, raw opt-out keeps separate tags, hash creates one meter per raw value, explicit registration/deregistration handles shared exported keys, and in-flight active gauge cleanup remains non-negative. | Tests fail against current raw-tag recorder. |
| 4 | Wire sanitizer into `MicrometerLeaderAopMetricsRecorder`; preserve the one-argument constructor and add options/sanitizer constructors. Track explicit registration counts by exported tag only for `registerMetricsFor`; ordinary callbacks must not store raw aliases. | Recorder tests pass; old constructor compiles and now emits `redacted-lock` by default. |
| 4a | Inventory current public JVM constructor descriptors for `MicrometerLeaderAopMetricsRecorder`, `InstrumentedLeaderElector`, `InstrumentedLeaderGroupElector`, `InstrumentedSuspendLeaderElector`, `MicrometerLeaderElectionListener`, and `MicrometerObservationLeaderAopMetricsRecorder`; add compile/API compatibility evidence. | Every pre-existing constructor descriptor is listed and preserved. New sanitizer-aware overloads are added beside, not instead of, existing overloads. Existing descriptors are preserved with explicit secondary constructors or compatible `@JvmOverloads` where safe, and Java/Kotlin source compatibility tests or equivalent bytecode/signature evidence prove old call sites still compile. |
| 4b | Add precise lifecycle tests for in-flight deregistration. | Test starts an active measurement, deregisters the same raw name and a colliding raw name while active, completes the task, and asserts no exception, no negative active value, and correct shared exported-meter retention/removal. |
| 5 | Add failing decorator/listener tests for `InstrumentedLeaderElector`, `InstrumentedLeaderGroupElector`, `InstrumentedSuspendLeaderElector`, and `MicrometerLeaderElectionListener` using the same sanitizer path. | Tests fail against current raw-tag decorators/listener. |
| 6 | Wire sanitizer into decorator/listener constructors while preserving existing constructor signatures and fixed `lockName` behavior after runtime/fixed-name selection. | Decorator/listener tests pass; raw-compatible behavior requires explicit `LeaderMetricTagOptions.Raw`. |
| 7 | Add failing Observation tests proving default Observation exports neither lock name nor leader ID, and opt-in values are sanitized for both `lock.name` and `leader.id`. | Tests fail because Observation currently exports raw opt-in values. |
| 8 | Add sanitizer support to `LeaderObservationOptions` / `MicrometerObservationLeaderAopMetricsRecorder`; preserve default no-export behavior. | Observation tests pass and cancellation/exception behavior remains unchanged. |
| 9 | Add failing Spring tests for `bluetape4k.leader.aop.metrics.tags.*` binding, invalid property validation including `TRUNCATE + maxLength <= 0`, custom `LeaderMetricTagSanitizer` bean override, custom `LeaderAopMetricsRecorder` backoff, and optional-class startup without `leader-micrometer` types. | Tests fail because Spring properties and sanitizer bean wiring do not exist. |
| 10 | Extend `LeaderAopProperties.Metrics` with local property models and map them to `leader-micrometer` options inside `LeaderMicrometerAutoConfiguration`; update Spring configuration metadata. | Spring tests pass; new Spring property data classes have English KDoc, implement `Serializable`, define `serialVersionUID`, and do not require micrometer classes in always-loaded property types. |
| 10a | Add `FilteredClassLoader` or equivalent optional-module Spring test. | `leader-spring-boot` starts with `LeaderMetricTagSanitizer` and other `leader-micrometer` classes filtered out; no always-loaded property/configuration class exposes micrometer types in method/property signatures. |
| 11 | Replace touched ad hoc concurrency stress loops with `MultithreadingTester` and add concurrent first-use coverage for collapsed exported keys across AOP recorder, decorator metrics, and listener counters. | Concurrency tests pass, final meter/cache counts prove exactly one meter per metric/tag combination for redacted collapsed values, no duplicate registration/race exception occurs, active counts/counters remain correct under simultaneous same-exported-key use, final active gauge is zero, and no touched test uses ad hoc `repeat` launch loops for stress. |
| 12 | Update `README.md`, `README.ko.md`, `leader-micrometer/README.md`, `leader-micrometer/README.ko.md`, `leader-spring-boot/README.md`, and `leader-spring-boot/README.ko.md`. | Docs cover production presets, raw opt-out, constructors, Spring properties, corrected Prometheus duration and active-gauge queries, collapsed-tag semantics, Observation opt-in behavior, HASH/allowlist misuse guidance, unsupported/not-affected surfaces, and raw audit/history boundary. |
| 12a | Add operator migration/rollback and caller examples to EN/KO docs. | Docs include exact rollback property `bluetape4k.leader.aop.metrics.tags.lock-name.mode=RAW`, restart/redeploy expectation, before/after Prometheus label examples, validation queries, explicit warning that rollback restores raw cardinality, direct constructor/decorator/Spring YAML recipes, partial migration with static allowlist, and Observation rules/API vs Spring support. |
| 12b | Add tag-surface and unsupported-capability tables to EN/KO docs. | Tables list `lock.name`, `leader.id`, `backend.name`, emitting path, default mode, API/property support, and "no effect unless emitted"; unsupported section covers custom user-created meters, global registry filtering, custom recorder bean backoff, audit/history storage, metric names, default Observation behavior, and new dependencies. |
| 12c | Add an audit/history boundary negative check. | Tests or static verification prove `LeaderMetricTagSanitizer` is not wired into audit/history sinks and raw audit/history values remain unchanged. |
| 13 | Run focused verification for sanitizer, recorder, decorators, listener, Observation, and Spring binding, then full affected module tests. | Test output is captured; failures are fixed before PR; full `:leader-micrometer:test :leader-spring-boot:test` is mandatory before closure. |
| 14 | Run static/document checks: `git diff --check`, `rg` for stale raw-default docs, generated Spring metadata coverage, sanitizer usage scope, and code-pattern scan for forbidden assertions, ad hoc stress loops, missing KDoc, and missing `serialVersionUID` in new data classes. | Checks are clean or documented with exact residual risk; metadata covers every `lock-name`, `leader-id`, and `backend-name` property for `mode`, `allow-list`, `deny-list`, `hash-length`, `max-length`, and `redacted-value`; sanitizer usage remains limited to Micrometer/Observation export paths and audit/history sinks remain raw-only; new `leader-spring-boot` property data classes are included in the KDoc/Serializable/`serialVersionUID` scan. |
| 15 | Create a concise lesson, commit with Lore trailers, create PR assigned to `debop`, mirror issue labels/milestone, verify live PR body metadata and DoD. | PR exists with final `## DoD Status`; issue/PR metadata is verified live. |

## Compatibility and Migration Notes

- Metric names remain unchanged.
- Existing public one-argument constructors remain callable.
- Existing callers using default constructors intentionally move from raw `lock.name` export to
  `redacted-lock`; callers that require raw tags must explicitly choose `LeaderMetricTagOptions.Raw`
  or Spring `mode=RAW`.
- Hash mode is not a cardinality limiter. It is pseudonymization and length normalization only.
- `HASH` is deterministic and unsalted; it is not anonymization or secret protection. It needs an
  application-owned reverse-lookup/risk process for incident use and is not the safe preset for
  tenant-derived dynamic names.
- Allowlists are only for bounded, non-sensitive, static names. Spring allowlist/denylist values
  are configuration data, not a secret-hiding mechanism.
- Dynamic raw aliases are not retained for ordinary callbacks; only explicit registrations
  participate in shared-key deregistration.
- Spring property classes must not reference `leader-micrometer` types directly because the metrics
  module is optional and guarded by `@ConditionalOnClass`.

## Verification Commands

```bash
./gradlew :leader-micrometer:test --tests '*LeaderMetricTag*'
./gradlew :leader-micrometer:test --tests '*Micrometer*' --tests '*Instrumented*' --tests '*LeaderElectionListener*' --tests '*Observation*'
./gradlew :leader-spring-boot:test --tests '*LeaderAopProperties*' --tests '*LeaderMicrometer*' --tests '*FilteredClassLoader*'
./gradlew :leader-micrometer:test :leader-spring-boot:test
git diff --check
rg -n 'raw `lock.name`|raw lock.name|repeat\\(|launch \\{' leader-micrometer/src/test leader-spring-boot/src/test README.md README.ko.md leader-micrometer/README.md leader-micrometer/README.ko.md leader-spring-boot/README.md leader-spring-boot/README.ko.md
rg -n 'LeaderMetricTagOptions.Raw|mode=RAW|allow-list|Observation|Unsupported|custom meters|audit/history|redacted-lock|backend.name|leader.id' README.md README.ko.md leader-micrometer/README.md leader-micrometer/README.ko.md leader-spring-boot/README.md leader-spring-boot/README.ko.md
rg -n 'LeaderMetricTagSanitizer|LeaderMetricTagOptions' leader-core leader-micrometer leader-spring-boot
```

## Step 3-R Self-Review

| Check | Result |
|---|---|
| Every spec requirement maps to a plan task | Pass: sanitizer, recorder, decorators, listener, Observation, Spring, docs, tests, and PR metadata are covered. |
| Ordering is implementable | Pass: tests introduce missing behavior before each implementation step. |
| Public compatibility covered | Pass: constructor preservation and raw opt-out are explicit tasks. |
| Spring conditional class risk covered | Pass: property types stay local; micrometer mapping happens only in conditional auto-config. |
| Concurrency and lifecycle covered | Pass: `MultithreadingTester`, shared exported registration, and active gauge cleanup are named. |
| Documentation locale parity covered | Pass: root and module EN/KO README files are listed. |
| Step 3-R P1 convergence | Pass after edit: TRUNCATE validation, Observation caller docs, and operator rollback/runbook are now acceptance-gated. |
