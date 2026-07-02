# Issue #530 Code Review

## Scope

- Issue: #530, `feat(leader-micrometer): add metric tag cardinality controls`
- Branch: `feat/issue-530-cardinality-controls`
- Milestone: `0.5.0`
- Review target: metric tag cardinality controls for `leader-micrometer`, Spring Boot binding, Observation integration, Prometheus dashboard docs, and README locale parity.

## Seven-Tier Gate Result

| Tier | Result | Evidence |
|---|---|---|
| Tier 1 Performance | PASS | HASH now reuses a `ThreadLocal<MessageDigest>`; duration PromQL aggregates numerator and denominator by `lock_name`. |
| Tier 2 Stability | PASS | Explicit raw-name registration is tracked per exported tag; deregistration keeps collapsed gauges until the last raw source is removed; in-flight active gauges are retained. Rerun result: P0=0, P1=0. |
| Tier 3 Security | PASS | Observation listener and recorder sanitize opt-in `lock.name` and `leader.id`; Spring Observation auto-config passes the same tag policy. Rerun result: P0=0, P1=0. |
| Tier 4 Operations | PASS | Fresh affected-module verification completed; prior evidence gap was closed by full module tests. |
| Tier 5 Developer/API | PASS | Existing constructors remain available for `LeaderObservationOptions` and `LeaderAopProperties.Metrics`; javap confirmed binary-compatible entry points. |
| Tier 6 User/Caller | PASS | README EN/KO now explains RAW, HASH, TRUNCATE, allowlist risk, and that built-in meters do not currently emit `backend.name`. Rerun result: P0=0, P1=0. |
| Tier 7 Evidence | PASS | Tracked review artifact and lessons are included before PR creation; README architecture diagram was updated after the cardinality-control documentation changed. |

Final blocking count: P0=0, P1=0.

## Findings And Fixes

- P1 stability: repeated `registerMetricsFor` calls and collapsed redacted tag cleanup could remove meters too early. Fixed by tracking raw registrations per exported tag and by guarding cleanup while the active gauge is positive.
- P1 security: Observation listener could expose raw `lock.name` when high-cardinality fields were enabled. Fixed by constructing `LeaderMetricTagSanitizer` from `LeaderObservationOptions.tagOptions` and sanitizing before emitting Observation key values.
- P1 Spring parity: Observation auto-configuration was not passing AOP metric tag policy into Observation options. Fixed recorder and listener bean creation so Spring-bound tag options apply to both meter and Observation paths.
- P2 performance: HASH mode created digest instances per call. Fixed with a thread-local SHA-256 digest and per-use reset.
- P2 operations: dashboard duration PromQL divided separately aggregated series. Fixed with matching `sum by (lock_name)` on sum and count.
- P2/P3 docs: HASH and backend-name semantics were ambiguous. README EN/KO now states deterministic unsalted hash risk, static allowlist expectations, and current `backend.name` emission status.
- Diagram drift: the reused `leader-micrometer` architecture diagram still described raw/high-cardinality tags without the shared sanitizer policy. Updated the SVG/PNG to show `LeaderMetricTagSanitizer`, sanitized meter/Observation tags, and REDACT/RAW/HASH/TRUNCATE guardrails.

## Verification

- `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test --tests '*LeaderMetricTagOptionsTest' --tests '*MicrometerLeaderAopMetricsRecorderTest' --tests '*InstrumentedLeaderElectorsTest' --tests '*MicrometerLeaderElectionListenerTest' --tests '*MicrometerObservationLeaderAopMetricsRecorderTest' --tests '*MicrometerObservationLeaderElectionListenerTest' --tests '*PrometheusExportTest' --tests '*LeaderAopPropertiesBindingTest' --tests '*LeaderMicrometerAutoConfigurationTest' --tests '*LeaderObservationAutoConfigurationTest'`
  - Result: PASS, Micrometer 75 passing, Spring 23 passing.
- `./gradlew :examples:prometheus-dashboard:test --tests '*PrometheusScrapeTest'`
  - Result: PASS, 1 passing.
- `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test :examples:prometheus-dashboard:test`
  - Result: PASS, 349 passing, `BUILD SUCCESSFUL in 1m 36s`.
- `javap -classpath leader-micrometer/build/classes/kotlin/main io.bluetape4k.leader.micrometer.LeaderObservationOptions`
  - Result: PASS, constructors include `(boolean, boolean, boolean, LeaderMetricTagOptions)`, `(boolean, boolean, boolean)`, and no-arg.
- `javap -classpath leader-spring-boot/build/classes/kotlin/main 'io.bluetape4k.leader.spring.aop.properties.LeaderAopProperties$Metrics'`
  - Result: PASS, constructors include `(boolean, Tags)`, `(boolean)`, and no-arg.
- `git diff --check`
  - Result: PASS.
- `jq empty leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json examples/prometheus-dashboard/provisioning/grafana/dashboards/leader-dashboard.json`
  - Result: PASS.
- Pattern scan for forbidden assertions, stale raw-lock docs, and ad hoc concurrency helpers.
  - Result: PASS for touched scope; concurrency coverage uses `MultithreadingTester`.
- `xmllint --noout docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - Result: PASS.
- `~/.local/bin/cairosvg docs/images/readme-diagrams/leader-micrometer-architecture-01.svg -o docs/images/readme-diagrams/leader-micrometer-architecture-01.png -s 2`
  - Result: PASS, PNG rendered at 3692x2240.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - Result: PASS, `geometry_failures=0`.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-endpoint-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - Result: PASS, `files=1`.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-mixed-corner-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - Result: PASS, `paths=16`, `q_bends=0`, `failures=0`; all connectors are straight lines in this asset.
- `python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-connector-audit.py docs/images/readme-diagrams/leader-micrometer-architecture-01.svg`
  - Result: PASS, `markers=5`, `connectors=16`, `cards=19`, `intrusions=0`, `crossings=0`.
- Full-size PNG inspection with `view_image`
  - Result: PASS, no visible text overflow, connector/card intrusion, label overlap, or clipped guardrail text.

## Residual Risk

- Full repository tests were not run. Verification covered affected Micrometer, Spring Boot, and Prometheus dashboard modules.
- Shutdown-time Mongo/Lettuce reconnect log noise appeared in full affected-module test output, but Gradle completed successfully with 349 passing tests.
