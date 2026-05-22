# Issue 345 leader-consul Implementation Plan

## Slice 1: Dependency Spike

1. Add a local dependency alias for `com.orbitz.consul:consul-client:1.5.3`.
2. Create a throwaway compile-only spike or small test fixture that proves:
   - session create with TTL/lock delay;
   - KV acquire and release with session id;
   - session renew and destroy;
   - endpoint wiring against `ConsulServer.Launcher.consul`.
3. If the client API is awkward or pulls unacceptable stale transitive
   dependencies, remove the alias and implement an internal Java 21 `HttpClient`
   boundary instead.

Exit criteria: one committed dependency decision note or code spike result.

## Slice 2: Module Skeleton

1. Add `leader-consul` to `settings.gradle.kts`.
2. Add module `build.gradle.kts` with:
   - `api(project(":bluetape4k-leader-core"))`;
   - chosen Consul client as `api` only if public constructors expose it;
   - `testImplementation(testFixtures(project(":bluetape4k-leader-core")))`;
   - `bluetape4k.junit5`, `bluetape4k.testcontainers`, coroutine test deps.
3. Register the module in `bluetape4k-leader-bom`.
4. Add `ConsulLeaderElectionOptions` and key/session validation helpers.
5. Add README placeholders explaining preview status and Consul TTL minimum.

Exit criteria: module compiles and option/unit tests pass.

## Slice 3: Single-Leader Blocking Elector

1. Add an internal `ConsulLockClient` boundary.
2. Implement session create/acquire/release/destroy/renew operations.
3. Implement `ConsulLeaderElector`.
4. Implement owner payload codec and state mapper.
5. Add unit tests for validation, key encoding, owner payload, and mocked lock
   client delegation.
6. Add ConsulServer integration tests for:
   - execute/release/reacquire;
   - contention skip returns `null`;
   - expired session takeover;
   - action failure cleanup;
   - caller-owned client lifecycle.

Exit criteria: `./gradlew :bluetape4k-leader-consul:test` passes locally.

## Slice 4: Coroutine Elector and Auto-Extend

1. Implement `ConsulSuspendLeaderElector`.
2. Wrap blocking client calls in `Dispatchers.IO` if needed.
3. Implement `ConsulLockExtendDelegate` via session renew.
4. Add sync and suspend auto-extend integration tests.
5. Add cancellation cleanup tests for suspend action cancellation.

Exit criteria: blocking and suspend tests pass, including long-action
auto-extension.

## Slice 5: Repository Wiring

1. Add CI path filters and module test job in `.github/workflows/ci.yml`.
2. Add nightly module coverage in `.github/workflows/nightly-tests.yml`.
3. Update root README pairs and module README pairs.
4. Add `docs/lessons/2026-05-22-issue-345-leader-consul.md`.

Exit criteria: `git diff --check`, module tests, and relevant compile lanes pass.

## Deferred Slices

- Group election.
- Spring Boot auto-configuration and AOP factories.
- Prefix watch/event publisher.
- Ktor example or operational example.

These are deferred to keep the first PR reviewable and to avoid locking public
contracts before the single-leader Consul semantics are proven against a real
server.
