# Issue #419 Strategic Election Example Design

## Context

Issue #419 requests a runnable example for the strategic leader-election API.
The root README already documents `StrategicLeaderElector`,
`StrategicSuspendLeaderElector`, `FifoElectionStrategy`,
`RandomElectionStrategy`, `ScoredElectionStrategy`, and `WeightedScorer`, but
the examples catalog has no backend-neutral scenario that shows weighted
candidate selection.

## Goals

- Add `examples/strategic-election` as a backend-neutral application module.
- Demonstrate a realistic weighted decision, such as choosing the healthiest
  node by capacity, success rate, and idle time.
- Keep the example deterministic and runnable with
  `./gradlew :examples:strategic-election:run`.
- Add tests that prove winner selection and skip behavior.
- Register the module in settings, root README locale set, CI path filters/jobs,
  and the scheduled/manual Examples workflow.

## Non-Goals

- No new `leader-core` public API.
- No backend-specific distributed lock or Testcontainers dependency.
- No benchmark or performance claim.
- No Spring Boot, Ktor, Micrometer, or Actuator integration.

## Design

`StrategicElectionDemo` will simulate three service nodes that report capacity,
health, and historical success/failure data. The demo maps those inputs to
`CandidateInfo` and uses a `ScoredElectionStrategy` with a custom
`CandidateScorer` composed through `WeightedScorer`.

The module should keep the domain classes serializable where they cross test or
report boundaries:

- `ServiceNodeProfile` models node capacity, health score, and historical
  outcome counts.
- `StrategicElectionReport` records the selected node, skipped nodes, and score
  table for README-friendly output.
- `StrategicElectionStatus` distinguishes `SELECTED` and `SKIPPED`.

The test should assert:

- the highest weighted candidate is selected deterministically;
- exactly one node executes the maintenance action;
- non-winner nodes return skip reports rather than throwing.

## Acceptance Criteria

- `./gradlew :examples:strategic-election:test` passes.
- `./gradlew :examples:strategic-election:run` is the documented runnable path.
- Root `README.md` and `README.ko.md` list the example.
- `settings.gradle.kts` includes `examples:strategic-election`.
- `.github/workflows/ci.yml` has a path filter, output, test job, and status
  dependency for the new example.
- `.github/workflows/examples.yml` includes the module in its matrix.
