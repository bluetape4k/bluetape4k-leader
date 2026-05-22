# 6-Tier Code Review - Issue #345 Consul Runtime Slice

Scope: `leader-consul` single-leader blocking and `CompletableFuture` runtime,
internal Java 21 HTTP boundary, owner payload/state mapping, tests, and README
status updates.

## Verdict

- Local Codex 6-Tier: APPROVE with follow-up P2.
- Claude final advisor: APPROVE with COMMENT.
- Gate: P0=0, P1=0.
- Claude artifacts:
  - `.omx/artifacts/ask-claude-code-review-consul-runtime-20260522232205.md`
  - `.omx/artifacts/ask-claude-code-review-consul-runtime-final-20260522232633.md`

## Tier 1 - Security

- PASS: Consul ACL token is passed only through `X-Consul-Token`; README and
  endpoint `toString()` avoid token disclosure.
- PASS: `keyPrefix` now rejects query/hash/control-like path injection by
  allowing only `[a-zA-Z0-9_\-./:]`.
- PASS: Lock names are validated by core rules and encoded as final path
  segments before Consul KV use.

## Tier 2 - Ops / SRE Reliability

- PASS: Normal contention returns `null`.
- PASS: Action failure releases/destroys the session and permits reacquire.
- PASS: Waiting candidates renew their own session when `waitTime` exceeds the
  Consul renew delay; this prevents `invalid session` during takeover.
- PASS: Interrupted `minLeaseTime` sleep restores the interrupt flag but still
  runs Consul `release`/`destroy`.
- WATCH: Blocking `.get(10, TimeUnit.SECONDS)` calls are not derived from
  `ConsulEndpoint.requestTimeout`. Track as follow-up before stable promotion.

## Tier 3 - Structural Impact

- PASS: Public API exposes only bluetape4k-owned `ConsulEndpoint`,
  `ConsulLeaderElectionOptions`, `ConsulLeaderElector`, and extension helpers.
- PASS: Consul HTTP details remain behind internal `ConsulLockClient`.
- WATCH: Custom `HttpClient`/TLS/proxy injection is not public in this slice.

## Tier 4 - Kotlin / Code Quality

- PASS: Cleanup paths preserve interruption semantics and avoid swallowing
  action exceptions through cleanup sleep failures.
- PASS: `ConsulLockExtendDelegate.isHeld()` is now a passive read-only ownership
  check, not a session renewal side effect.
- PASS: Public APIs have English KDoc.

## Tier 5 - Tests / Types / Silent Failure

- PASS: `:bluetape4k-leader-consul:test` executes 25 tests.
- PASS: Tests cover options/key validation, owner payload roundtrip, backend
  error classification, state mapping, contention skip, destroy failure on
  contention, interrupted cleanup, waiting-candidate renewal, async happy path,
  `LeaderRunResult.ActionFailed`, action-failure cleanup, and real Consul TTL
  takeover.
- WATCH: Release/destroy failures remain best-effort WARN-only by design.

## Tier 6 - Performance / Stability

- PASS: Polling loop has a fixed 50 ms sleep and a hard `waitTime` deadline.
- PASS: Real Consul expiry takeover test uses a wider timeout budget to account
  for Consul TTL expiry variance.
- WATCH: Async release can block the caller-provided executor for
  `minLeaseTime`; current README/defaults favor virtual threads, but a future
  cleanup executor or documentation note would reduce surprises.

## Validation

- `git diff --check`
- `./gradlew :bluetape4k-leader-consul:test --no-daemon --console=plain`
  - PASS: 25 tests.
- `./gradlew :bluetape4k-leader-consul:check --no-daemon --console=plain`
  - PASS.

## Follow-Up Candidates

- Derive blocking `.get(...)` limits from endpoint/request timeout policy.
- Decide whether Consul elector should expose custom `HttpClient` configuration.
- Document or isolate executor blocking in async cleanup when `minLeaseTime` is
  non-zero.
