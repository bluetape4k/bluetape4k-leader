# Lesson - Issue #524 Kubernetes Lease Scenario Benchmarks

## Context

Issue #524 required K3s-backed Kubernetes Lease benchmarks beyond the basic
successful `runIfLeader` path. The missing cases were active-holder skip,
expired takeover, renewal, and resource-version conflict behavior across
blocking and suspend lanes.

## Decision

Keep public elector benchmarks for the scenarios the public API can model:
fresh acquire, active-holder skip, and expired takeover. Model same-holder
renewal and stale `resourceVersion` conflict as direct Fabric8 Lease API probes
because public electors generate an internal owner token per acquisition and
release clears the holder by default.

## Outcome

The results show that skip paths are cheapest because they avoid Lease writes,
while fresh acquire and expired takeover include public elector acquire and
release work. Renewal and conflict rows now provide API-server update/conflict
cost visibility without pretending they are full user action paths.

## Future Guard

When Kubernetes Lease benchmark rows mix public elector paths with direct Lease
API probes, keep that boundary explicit in README tables, benchmark reports,
and chart captions. Do not compare direct renewal/conflict probes as if they
were complete acquire+release user workflows.
