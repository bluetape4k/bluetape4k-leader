# Issue 328 benchmark README and charts

## Context

Issue #328 asked for published benchmark reports and charts after the
cross-backend benchmark suites were added. The raw baseline existed in
`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`, but README
readers had no direct entry point from the module or repository root.

## Decision

Keep the full result tables in `benchmark/README.md` and
`benchmark/README.ko.md`, then expose a smaller benchmark summary from the root
README files. Chart only the distributed backend rows for remote comparisons so
local and H2 rows do not compress the Redis, Hazelcast, MongoDB, and ZooKeeper
differences.

## Outcome

The benchmark module now documents the `kotlinx-benchmark`/JMH command, source
set, baseline caveats, throughput and latency charts, cross-backend tables, and
local core overhead rows. Root README files link to the benchmark module and
baseline report with a concise interpretation table.

## Verification

Run `git diff --check` and markdown link/path checks after editing. No Gradle
execution is required for this docs-only change unless benchmark source or
Gradle configuration changes.

## Future Guidance

When benchmarks are rerun, update the baseline report, both benchmark README
files, and the SVG charts in the same PR. Keep local/H2 rows out of remote
backend charts unless a separate scale or panel is added.
