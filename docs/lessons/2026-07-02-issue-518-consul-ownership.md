# Issue 518 - Consul Extension Ownership

## Context

Consul lock extension renewed the session and returned `Extended` without
checking that the KV lock key still belonged to the same session. A renewable
session is not proof of lock ownership after the KV key is deleted, released, or
acquired by another session.

## Decision

Treat Consul extension as a two-part proof: session renewal must succeed and
the KV entry must still point at the same session id. Return `NotHeld` before
updating `lastExtendDeadline` when the KV entry is missing or owned by a
different session.

## Test Guard

Add deterministic delegation tests for both sync and suspend delegates where
`renewSession` succeeds but `read(key)` returns another session. Also keep fake
Consul clients stateful enough that successful `acquire` writes the current KV
owner; otherwise normal extension tests do not model Consul ownership after the
new guard.

## Outcome

Sync and suspend Consul extension no longer mask lock loss. Explicit extension
and watchdog extension paths now stop with `NotHeld` when the KV owner moved.

## Verification

- Targeted RED: both new tests failed against the old implementation with
  `Extended(...)`.
- Targeted GREEN: the two new ownership mismatch tests passed.
- Full `leader-consul` test: 60 tests passed in 36s.
- Review gate: P0/P1/P2/P3 = 0.
- `git diff --check`: pass.
