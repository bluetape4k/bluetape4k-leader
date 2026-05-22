# Issue 345 leader-consul Implementation Plan

## Slice 0: Gate and Fixture Preconditions

1. Run `codex run spec --retry 3` and `codex run plan --retry 3` or record the
   current-session equivalent review artifact when the user explicitly keeps
   review in-session.
2. Run the Claude Code CLI advisor against spec + plan and require P0/P1 = 0.
3. Verify `io.bluetape4k.testcontainers.infra.ConsulServer` is available from
   `bluetape4k-testcontainers` resolved through `bluetape4k-bom:1.9.0`; if
   absent, stop and add the fixture upstream before implementing this repository
   slice.
4. Confirm dependency research: `com.orbitz.consul:consul-client` latest is
   stale (`1.5.3`, 2021), so the implementation uses an internal Java 21
   `HttpClient` boundary and publishes no third-party Consul client type.

Exit criteria: fixture availability confirmed, dependency decision recorded,
and advisor P0/P1 gate closed.

## Slice 1: HTTP Boundary Spike

1. Add internal DTOs for the minimal Consul HTTP surface:
   - session create with TTL/lock delay;
   - KV acquire and release with session id;
   - session renew and destroy;
   - endpoint wiring against `ConsulServer.Launcher.consul`.
2. Implement the spike with Java 21 `HttpClient` and repo-local JSON handling.
3. Keep all boundary types internal except the bluetape4k-owned endpoint/config
   DTO used by public constructors.

Exit criteria: committed dependency decision note and compile-tested boundary
shape.

## Slice 2: Module Skeleton

1. Add `leader-consul` to `settings.gradle.kts`.
2. Add module `build.gradle.kts` with:
   - `api(project(":bluetape4k-leader-core"))`;
   - no external Consul client dependency;
   - `testImplementation(testFixtures(project(":bluetape4k-leader-core")))`;
   - `bluetape4k.junit5`, `bluetape4k.testcontainers`, coroutine test deps.
3. Register the module in `bluetape4k-leader-bom`.
4. Add `ConsulEndpoint`, `ConsulLeaderElectionOptions`, and key/session
   validation helpers.
5. Add README placeholders explaining preview status, Consul TTL minimum and
   maximum, zero `lockDelay` overlap risk, and idempotent/fencing guidance.

Exit criteria: module compiles and option/unit tests pass.

## Slice 3: Single-Leader Blocking Elector

1. Add an internal `ConsulLockClient` boundary.
2. Implement session create/acquire/release/destroy/renew operations.
3. Implement `ConsulLeaderElector`.
4. Implement owner payload codec and state mapper.
5. Add unit tests for validation, key encoding, owner payload, HTTP error
   classification, and mocked lock client delegation.
6. Add ConsulServer integration tests for:
   - execute/release/reacquire;
   - contention skip returns `null`;
   - expired session takeover;
   - action failure cleanup;
   - best-effort destroy failure on contention still returns `null`.

Exit criteria: `./gradlew :bluetape4k-leader-consul:test` passes locally.

## Slice 4: Coroutine Elector and Auto-Extend

1. Implement `ConsulSuspendLeaderElector`.
2. Wrap blocking client calls in `Dispatchers.IO` if needed.
3. Implement `ConsulLockExtendDelegate` via session renew.
4. Renew at `min(leaseTime / 3, leaseTime - 2.seconds)` after validating the
   Consul TTL floor/ceiling.
5. Rethrow `CancellationException` and release/destroy the session in
   `NonCancellable` cleanup for suspend action cancellation.
6. Add sync and suspend auto-extend integration tests.
7. Add cancellation cleanup tests for suspend action cancellation.

Exit criteria: blocking and suspend tests pass, including long-action
auto-extension.

## Slice 5: Repository Wiring

1. Add CI path filters and module test job in `.github/workflows/ci.yml`.
2. Add nightly module coverage in `.github/workflows/nightly-tests.yml`.
3. Set integration-heavy Kover expectations to 60% for this module.
4. Update root README pairs and module README pairs.
5. Add `docs/lessons/2026-05-22-issue-345-leader-consul.md`.
6. Run current-session code review plus Claude Code CLI code review on the
   changed diff; resolve P0/P1 before PR.

Exit criteria: `git diff --check`, module tests, and relevant compile lanes pass.

## Deferred Slices

- Group election.
- Spring Boot auto-configuration and AOP factories.
- Prefix watch/event publisher.
- Ktor example or operational example.

These are deferred to keep the first PR reviewable and to avoid locking public
contracts before the single-leader Consul semantics are proven against a real
server.
