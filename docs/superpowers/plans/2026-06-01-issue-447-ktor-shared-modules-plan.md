# Issue 447 Ktor Shared Module Adoption Plan

## Context

`bluetape4k-projects` 1.10.0 publishes shared Ktor modules. `bluetape4k-leader`
already depends on the 1.10.0 BOM and has two Ktor surfaces:

- `leader-ktor`: leader-election plugin, scheduler integration, and management route.
- `examples/ktor-app`: runnable Ktor example with generic health and JSON test assertions.

## Decision

Keep `leader-ktor` leader-specific. Adopt shared Ktor modules only where they
own generic behavior:

- Use `bluetape4k-ktor-core` health/readiness routes in `examples/ktor-app`.
- Keep Jackson `ContentNegotiation` in the example because its public `/stats`
  response contains `java.time.Instant`.
- Use `bluetape4k-ktor-testing` response status and JSON decode assertions for
  the shared health/readiness response.

## Verification

- Run `./gradlew :bluetape4k-leader-ktor:test :examples:ktor-app:test --no-daemon`.
- Run `git diff --check`.
