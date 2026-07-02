# Issue 518 - Consul Extension Ownership Review

## Scope

- Issue: #518 - Consul extension renews session without verifying KV ownership
- Module: `leader-consul`
- Files:
  - `ConsulLockExtendDelegate.kt`
  - `ConsulSuspendLockExtendDelegate.kt`
  - `ConsulLeaderElectorDelegationTest.kt`
  - `ConsulSuspendLeaderElectorDelegationTest.kt`

## Review Result

P0/P1/P2/P3: 0.

Sync and suspend Consul extension now renew the Consul session and then verify
that the KV lock entry still belongs to the same session before returning
`Extended`. A missing entry or different session returns `NotHeld` and leaves
`lastExtendDeadline` unchanged. Backend read failures remain `BackendError`.

## Evidence

- `ConsulLockExtendDelegate.kt`: `extend(...)` reads the KV entry after session
  renewal and returns `NotHeld` before updating the deadline when ownership is
  gone.
- `ConsulSuspendLockExtendDelegate.kt`: `extendSuspend(...)` mirrors the sync
  ownership guard and still rethrows `CancellationException`.
- `ConsulLeaderElectorDelegationTest`: sync mismatch test proves successful
  renew no longer masks a moved KV owner.
- `ConsulSuspendLeaderElectorDelegationTest`: suspend mismatch test proves the
  same `NotHeld` and deadline behavior.
- Fake Consul clients now model successful acquire by writing a session-owned
  KV entry, so normal extension tests still represent Consul state.

## Verification

- RED targeted test: new sync and suspend tests failed against the old
  implementation with `Extended(...)` instead of `NotHeld`.
- Targeted GREEN:
  `./gradlew :bluetape4k-leader-consul:test --tests "...ConsulLeaderElectorDelegationTest.extend returns NotHeld when Consul KV ownership moved to another session" --tests "...ConsulSuspendLeaderElectorDelegationTest.extendSuspend returns NotHeld when Consul KV ownership moved to another session" --no-parallel --warning-mode all --no-daemon --console=plain`
  passed 2 tests.
- Full module:
  `./gradlew :bluetape4k-leader-consul:test --no-parallel --warning-mode all --no-daemon --console=plain`
  passed 60 tests in 36s.
- `git diff --check`: pass.

## Tooling Notes

- CodeGraph impact was consulted before implementation. Its Kotlin graph is
  stale enough to over-report blast radius and later report zero changed
  functions, so direct diff review plus targeted/full Gradle tests are the
  primary gate evidence for this fix.
- IntelliJ diagnostics MCP was not available in this session; Gradle compile
  and module tests were used as the fallback diagnostics gate.

## Concurrency Test Gate

No concurrency helper was used because this issue is a deterministic ownership
state check, not a thread race, contention stress, coroutine cancellation stress,
or watchdog scheduling test. The regression is locked by fake Consul state:
session renew succeeds while the KV entry belongs to another session.
