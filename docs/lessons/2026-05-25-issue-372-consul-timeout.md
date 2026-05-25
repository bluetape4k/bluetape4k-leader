# Issue 372 Consul request timeout waits

## Context

Issue #372 tracked hard-coded `10s` waits in the Consul backend. The HTTP
client already reads `ConsulEndpoint.requestTimeout`, but blocking callers still
used unrelated `CompletableFuture.get(10, TimeUnit.SECONDS)` budgets.

## Decision

Expose `requestTimeout` through the internal `ConsulLockClient` boundary and
centralize blocking waits in `getWithinRequestTimeout`. This keeps the public
API unchanged while making blocking, async, and synchronous state snapshot paths
use the endpoint-configured budget.

## Outcome

Consul blocking wait sites no longer hard-code `10s`. Delegation tests record
the timeout passed to `CompletableFuture.get` for blocking and suspend
single/group state snapshots.

## Verification

- `git diff --check`
- `./gradlew :bluetape4k-leader-consul:test --tests '*DelegationTest' --no-daemon` (22 passing)
- `./gradlew :bluetape4k-leader-consul:test --tests '*Consul*' --no-daemon` (56 passing)
- Claude Code review artifact: `.omx/artifacts/claude-issue-372-consul-timeout-20260525112738.md` (P0=0, P1=0)

## Future Guard

When a backend has both an HTTP/client timeout and a blocking future wait,
derive both from the same backend configuration or document the intentionally
different safety margin. Do not leave fixed `10s` waits in backend cleanup,
state, or extension paths without a named policy.
