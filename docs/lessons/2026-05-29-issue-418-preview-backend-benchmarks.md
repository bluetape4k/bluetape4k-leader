# Issue 418 Preview Backend Benchmarks

## Context

The benchmark module had cross-backend rows for stable backends but no preview
rows for Consul, etcd, DynamoDB, or Kubernetes.

## Decision

Keep preview rows in the existing non-published `benchmark` module and reuse
backend integration-test Testcontainers launchers. Do not create raw JMH setup
or production benchmark helpers. Run Kubernetes from a separate
`kubernetesBenchmark` source set because Fabric8 needs Vert.x 4 / Netty 4.1 and
the default preview backend classpath needs Vert.x 5 for etcd.

## Outcome

Blocking and suspend benchmark harnesses now compile with Consul, etcd,
DynamoDB Local, and K3s-backed Kubernetes Lease rows. Kubernetes is isolated in
its own benchmark target. Each setup still performs a smoke `runIfLeader` check
before timing.

## Verification

- `./gradlew :benchmark:compileBenchmarkKotlin :benchmark:compileKubernetesBenchmarkKotlin --no-daemon` passed on 2026-05-29.
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon` passed on 2026-05-29.
- `./gradlew :benchmark:kubernetesBenchmarkBenchmark :benchmark:kubernetesBenchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon` passed on 2026-05-29.
- Raw JSON was preserved under `docs/benchmarks/2026-05-29-issue-418-*.json`.

## Future Guard

For benchmark README result tables, add or change measured rows only after a
fresh same-branch JSON result exists. Kubernetes benchmark rows are useful but
require Docker/K3s-capable local execution, should be treated as heavier than
the other preview rows, and should remain isolated unless Fabric8 and etcd can
share one Vert.x line.
