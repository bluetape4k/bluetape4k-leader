# Review - Issue #524 Kubernetes Lease Scenario Benchmarks

Scope:

- `benchmark/src/kubernetesBenchmark/kotlin/io/bluetape4k/leader/benchmark/KubernetesBackendLeaderElectorBenchmark.kt`
- `benchmark/README.md`
- `benchmark/README.ko.md`
- `docs/benchmarks/2026-07-02-issue-524-kubernetes-lease-scenarios.md`
- `docs/benchmarks/2026-07-02-issue-524-kubernetes-scenarios-*.json`
- `docs/images/readme-charts/leader-kubernetes-scenarios-*-chart-01.{svg,png}`

## Findings

No P0/P1 findings.

## Review Notes

- The benchmark remains isolated in the `kubernetesBenchmark` source set so the
  Fabric8 Vert.x 4 runtime line does not alter the default preview backend
  classpath.
- Public elector rows cover fresh acquire, active-holder skip, and expired
  takeover for both blocking and suspend lanes.
- Same-holder renewal and stale `resourceVersion` conflict rows are documented
  as direct Lease API probes. They intentionally measure API-server update and
  conflict behavior, not full user action execution.
- JMH state fixtures reset Lease resources at invocation or trial scope based
  on scenario state, preventing prior iterations from converting fresh or
  expired paths into same-holder paths.
- CodeGraph review returned no changed function nodes for the benchmark source
  set, so manual diff review plus compile, K3s JMH smoke, chart QA, README
  switch validation, and `git diff --check` were used as the review evidence.

## 7-Tier Gate

| Tier | Verdict | Evidence |
|---|---|---|
| Tier 1 Security | PASS | No production auth, credential, or network policy boundary changed; the benchmark uses the existing local K3s Testcontainers wrapper. |
| Tier 2 Architecture | PASS | New coverage stays in the existing benchmark source set and does not change production leader APIs. |
| Tier 3 Data/State | PASS | Lease fixtures are created, updated, cleaned, and isolated by scenario-specific JMH state. |
| Tier 4 Correctness | PASS | Blocking and suspend rows cover fresh acquire, pre-held skip, expired takeover, renewal update, and stale conflict scenarios. |
| Tier 5 Test/Benchmark | PASS | `compileKubernetesBenchmarkKotlin`, `kubernetesBenchmarkBenchmarkJar`, and two K3s JMH smoke runs produced 10 throughput rows and 10 average-time rows. |
| Tier 6 Performance | PASS | README and benchmark report state that the numbers are short K3s smoke snapshots and separate direct API probes from full elector paths. |
| Tier 7 Docs/Release | PASS | README and README.ko were updated together with raw JSON, generated SVG/PNG charts, commands, tables, and interpretation. |

P0: 0  
P1: 0
