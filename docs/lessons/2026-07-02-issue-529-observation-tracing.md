# Issue #529 Observation Tracing Lessons

## Context

#529 added Micrometer Observation support beside existing Micrometer meters.
The request expanded from tests to include example code and detailed README
coverage.

## Decisions

- Emit standalone terminal observations instead of long-lived spans because the
  current AOP metrics SPI has no per-invocation id for safe same-lock pairing.
- Keep raw lock names, leader IDs, and raw throwable details opt-in.
- Do not synthesize `leader.id` in Spring AOP. Current annotations do not expose
  a real leader identity contract.
- Defer lease-extension Observation to #559 because `LockExtender` needs a core
  hook before Micrometer can record extension outcomes consistently.

## Outcome

- `leader-micrometer` now provides Observation recorder/listener APIs.
- `leader-spring-boot` auto-configures Observation recorder/listener beans when
  `ObservationRegistry` is present.
- `examples/prometheus-dashboard` includes a local demo `ObservationHandler`.
- README and README.ko files describe direct, Spring Boot, and example usage.

## Verification

- Focused Gradle tests passed for micrometer Observation, spring Observation
  auto-configuration, existing Micrometer auto-configuration, and the Prometheus
  dashboard example.
- Dependency checks confirmed this issue did not add OpenTelemetry SDK,
  Micrometer tracing bridge, exporter, or collector dependencies.
- `git diff --check` passed.

## Future Guard

Elapsed numeric values must not be low-cardinality Observation key values.
Use high-cardinality keys or a non-tag context mechanism for unbounded numeric
diagnostics.
