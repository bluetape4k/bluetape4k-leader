# Issue 215-218 History Review Fixes

## Context

PR #214 added leader history audit backends and wrappers. Follow-up review found
four gaps: Mongo lock token entropy, Exposed history completion ownership checks,
suspend-only retention auto-configuration, and test assertion style drift.

## Decision

- Mongo lock tokens must use 22 Base58 characters to preserve at least 128 bits
  of entropy.
- Exposed JDBC/R2DBC history completion must update by `id AND token`, not `id`
  alone.
- Retention auto-configuration should gate on AOP elector factories and sinks,
  not concrete elector instances.
- Reactive scheduled methods should avoid doing AOP metadata/factory resolution
  during Spring's startup-time `@Scheduled` publisher inspection.

## Outcome

Added regression tests for Mongo token length, Exposed token mismatch updates,
and suspend retention registration without a blocking `LeaderElector` bean.
Migrated touched history tests away from `kotlin.test` assertions.

Claude review found one valid blocker after the first pass: the suspend
retention wrapper briefly used `runBlocking` in production code. Accepted fix:
keep the `@Scheduled` wrapper split, but make the guarded method return
`mono(Dispatchers.IO) { ... }.then()`. Claude also suggested dropping the
`SuspendLeaderElectorFactory` condition, but that was rejected because the
current `LeaderElectionAspect` Mono branch resolves and uses a
`SuspendLeaderElectorFactory`.

## Verification

- `./gradlew --no-daemon :leader-core:test :leader-micrometer:test :leader-mongodb:test :leader-exposed-jdbc:test :leader-exposed-r2dbc:test :leader-spring-boot:test`
- `./gradlew --no-daemon :leader-spring-boot:compileKotlin`
- IDE diagnostics: no build errors reported.

## Future Guard

When reviewing `@Scheduled` methods returning `Mono`, verify startup behavior
with `ApplicationContextRunner`; Spring invokes reactive scheduled methods once
at context initialization to obtain the publisher.
Do not use `runBlocking` to bridge scheduled coroutine retention work; use a
deferred Reactor bridge and keep blocking only at the outer `@Scheduled`
platform-thread boundary when the schedule contract requires it.
