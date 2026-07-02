# Issue #529 Observation Tracing Review

## Scope

- `leader-micrometer` Observation recorder/listener and public constants.
- `leader-spring-boot` Observation auto-configuration, property binding, and meter-recorder coexistence.
- `examples/prometheus-dashboard` demo Observation handler and README coverage.

## 7-Tier Verdict

| Tier | Verdict | Evidence |
|---|---|---|
| Security | PASS | Raw lock name, leader ID, and raw throwable export are opt-in. `includeExceptionDetails=false` by default. README warns that values are not redacted. |
| Performance | PASS | No per-lock queues or background workers. Recorder no-ops attempt/start callbacks and returns immediately for `ObservationRegistry.NOOP`. |
| Stability | PASS | Observations are short terminal events, so same-lock start/stop pairing cannot leak scopes. `MultithreadingTester` covers concurrent terminal callbacks. |
| API/Compatibility | PASS | Existing meter names stay unchanged. Custom generic `LeaderAopMetricsRecorder` still suppresses default meter recorder; custom Observation recorder does not. |
| Spring/Config | PASS | `LeaderObservationAutoConfiguration` is ordered after metrics and before AOP; properties are guarded by parent and child switches. |
| Docs/Examples | PASS | English/Korean README files describe direct API, Spring properties, demo handler, cardinality risks, and #559 lease-extension follow-up. |
| Evidence | PASS_WITH_NOTE | Focused tests pass. Full three-module run failed in pre-existing Redis-backed spring tests with `Connection refused` to `localhost:34545`; new tests passed in that run. |

## Findings

No P0/P1 findings remain.

Resolved during review:

- `acquire.elapsed.ms` and `execution.elapsed.ms` were initially low-cardinality Observation keys. They now use high-cardinality keys because elapsed values are unbounded.
- Current Spring AOP does not expose real leader identity. The implementation does not synthesize `leader.id` from node IDs or lock names; docs say `include-leader-id=true` requires `LeaderAopMetricsContext.Identified`.

## Validation Evidence

| Command | Result |
|---|---|
| `./gradlew :bluetape4k-leader-micrometer:test --tests '*MicrometerObservation*' :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --tests '*LeaderMicrometerAutoConfigurationTest' :examples:prometheus-dashboard:test --no-daemon --no-configuration-cache --console=plain` | PASS: 16 micrometer tests, 16 spring tests, 1 example test |
| `./gradlew :examples:prometheus-dashboard:compileKotlin :examples:prometheus-dashboard:compileTestKotlin --no-daemon --no-configuration-cache --console=plain` | PASS |
| `./gradlew :bluetape4k-leader-micrometer:dependencies --configuration runtimeClasspath ...` and `:bluetape4k-leader-spring-boot:dependencies --configuration runtimeClasspath ...` | PASS: no `opentelemetry-*`, `micrometer-tracing-bridge`, exporter, or collector matches |
| `git diff --check` | PASS |
| Full attempted run: `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test :examples:prometheus-dashboard:test ...` | FAIL in existing Redis-backed spring tests: Redisson/Lettuce connection refused to `localhost:34545`; new observation tests passed |

## Residual Risk

- Lease-extension Observation requires a core hook and is tracked in #559.
- True long-lived spans require a future per-invocation AOP/core SPI; #529 deliberately emits standalone terminal observations.
