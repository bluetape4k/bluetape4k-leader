# Lessons — Issue 406 etcd richer metadata

Date: 2026-05-29
Issue: #406
Branch: test/412-design-406-k8s-nightly-etcd-metadata

## Context

`leader-etcd` uses jetcd Lock service ownership keys for correctness. That gives safe lease-bound ownership but only
opaque backend tokens for state snapshots unless another metadata channel is added.

## Decision

Keep Lock service as the 0.3.0 correctness primitive. Do not migrate to Election service or raw KV transactions for
metadata alone. If richer `LeaderState` or `LeaderGroupState` becomes necessary, add sidecar metadata keys attached
to the same lease and treat them as observability-only.

## Outcome

The design note records the selected approach, rejected alternatives, sidecar key/value shape, correctness rules,
and future EtcdServer-backed test list.

## Verification

- `./gradlew :bluetape4k-leader-etcd:test --no-daemon` passed with 66 tests.
- `git diff --check` passed.

## Future Guard

Do not let sidecar metadata decide etcd leadership. Always confirm the current owner through the Lock service
ownership key ordering before mapping metadata into state or event output.
