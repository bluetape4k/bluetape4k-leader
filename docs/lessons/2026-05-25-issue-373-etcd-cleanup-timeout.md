# Issue 373 etcd cleanup timeout policy

## Context

Issue #373 tracked fixed `10s` waits in etcd cleanup paths. Single-leader
cleanup used the hard-coded budget for unlock and lease revoke, and the same
pattern existed in the blocking group elector.

## Decision

Keep the public options unchanged and derive an internal cleanup timeout from
existing election settings: `max(waitTime, retryDelay)`. This preserves the
configured acquisition budget for normal cases while keeping cleanup bounded by
at least the backend retry delay when callers use `waitTime = 0`.

## Outcome

Blocking single and group etcd cleanup now use a named internal timeout helper
instead of fixed `10s` waits. Unit coverage records the timeout passed to
`CompletableFuture.get` for single cleanup, zero-wait fallback cleanup, and
group cleanup.

## Verification

- `git diff --check`
- `./gradlew :bluetape4k-leader-etcd:test --tests '*EtcdLeaderCleanupTimeoutTest' --tests '*EtcdLeaderElector*' --no-daemon` (9 passing)
- `./gradlew :bluetape4k-leader-etcd:test --tests '*Etcd*' --no-daemon` (64 passing)
- Claude Code review artifact: `.omx/artifacts/claude-issue-373-etcd-cleanup-timeout-20260525114258.md` (P0=0, P1=0)

## Future Guard

For cleanup waits, avoid anonymous fixed constants. Use the configured backend
budget when one exists, or introduce a named internal policy and test the exact
timeout passed to blocking futures.
