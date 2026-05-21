# Lessons Learned - issue #329 leader history recorder self-improve (2026-05-21)

**Related issue**: #329
**Affected module**: `leader-core`

## Context

Issue #329 required benchmark-guided performance improvement after the central
`benchmark/` module had made leader benchmark rows comparable. The primary gate
was `HistoryRecorderBenchmark.blockingInMemoryAcquireComplete` throughput, with
no more than 5% regression in core guard rows.

## Decision

The accepted optimization was a safe-path sanitizer change in
`LeaderHistoryRecorderSupport`: return original strings and metadata maps when
they are already within recorder limits, and allocate sanitized copies only when
replacement or truncation is required.

## Outcome

The first candidate passed the gate:

- `blockingInMemoryAcquireComplete`: 5,601,881.043 -> 20,018,125.709 ops/s
  (+257.35%).
- `blockingNoopAcquireComplete`: 7,642,848.188 -> 62,740,146.724 ops/s
  (+720.90%).
- `suspendInMemoryAcquireComplete`: 4,843,511.108 -> 11,441,889.888 ops/s
  (+136.23%).

## Verification Evidence

- Targeted support tests passed.
- Full `:benchmark:benchmarkBenchmark` throughput run passed before and after.
- Self-improve sealed-file validation passed after the code change.

## Future Guard

For hot-path audit/history code, first look for avoidable allocation in the
safe case before changing backend semantics or token generation. Keep the
benchmark harness sealed for before/after comparisons, and repeat longer
profiles before publishing release-grade performance claims.
