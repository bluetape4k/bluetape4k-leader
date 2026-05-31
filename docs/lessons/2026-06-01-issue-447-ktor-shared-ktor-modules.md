# Issue 447 Shared Ktor Module Adoption

## Context

Milestone 0.3.0 issue #447 asked for auditing `leader-ktor` and
`examples/ktor-app` after `bluetape4k-projects` 1.10.0 published shared Ktor
modules.

## Decision

`leader-ktor` stayed domain-specific because its plugin, scheduler extension,
and management route are leader-election APIs. `examples/ktor-app` adopted
`bluetape4k-ktor-core` for health/readiness routes and `bluetape4k-ktor-testing`
for response assertions.

The example kept Jackson content negotiation because `/stats` exposes
`Instant`; replacing it with the shared kotlinx JSON installer would change the
serialization contract more than this issue requires.

## Outcome

`GET /health` now comes from shared Ktor core, and the example also exposes the
shared readiness route at `GET /readyz`.

## Verification

- `./gradlew :bluetape4k-leader-ktor:test :examples:ktor-app:test --no-daemon`
  passed with 17 `leader-ktor` tests and 7 `examples:ktor-app` tests.
- `git diff --check` passed.
