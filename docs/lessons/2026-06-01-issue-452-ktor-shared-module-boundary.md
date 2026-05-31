# Issue 452 - Ktor shared module boundary

## Context

`bluetape4k-leader` has two Ktor-facing surfaces: the publishable
`leader-ktor` integration module and the runnable `examples:ktor-app`.
`bluetape4k-projects` now provides shared `bluetape4k-ktor-core` and
`bluetape4k-ktor-testing` artifacts, so the leader repo needed a consistency
pass.

## Decision

- `examples:ktor-app` uses `installBluetape4kKtorCore` for shared
  health/readiness routes while keeping Jackson `ContentNegotiation` for the
  `/stats` response because it exposes `java.time.Instant`.
- `leader-ktor` uses `bluetape4k-ktor-testing` in tests for response status
  assertions.
- `leader-ktor` does not add a runtime dependency on `bluetape4k-ktor-core`.
  Its public surface is the leader plugin/scheduler DSL, and the management
  route intentionally emits JSON text without requiring content negotiation.

## Outcome

Ktor consumers now document the shared module pattern explicitly without
introducing a circular or misleading runtime dependency boundary.

## Verification

- `./gradlew :bluetape4k-leader-ktor:test :examples:ktor-app:test --no-daemon`
- `git diff --check`

## Future Guidance

When adding a Ktor example, use `bluetape4k-ktor-core` for app-level JSON,
error, health, and readiness helpers and `bluetape4k-ktor-testing` for response
assertions. Keep integration modules free of shared runtime dependencies unless
their production code directly needs the helper behavior.
