# Issue #524 Kubernetes Lease Scenario Benchmarks

This benchmark expands the K3s-backed Kubernetes Lease coverage from the basic
successful `runIfLeader` path to stateful Lease scenarios:

- fresh acquire through the public elector
- pre-held Lease skip through the public elector
- expired holder takeover through the public elector
- same-holder Lease renewal update as a direct Kubernetes API-server probe
- stale `resourceVersion` update conflict as a direct Kubernetes API-server probe

The renewal and conflict rows intentionally isolate Kubernetes Lease API-server
update/conflict latency. They are not action execution costs and should not be
ranked as full elector acquire+release rows.

## Commands

Gradle task discovery and JMH jar creation:

```bash
./gradlew :benchmark:tasks --all --no-daemon --no-configuration-cache --console=plain
./gradlew :benchmark:kubernetesBenchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain
```

Measured smoke snapshot:

```bash
java -jar benchmark/build/benchmarks/kubernetesBenchmark/jars/benchmark-kubernetesBenchmark-jmh-0.5.0-JMH.jar \
  '.*KubernetesBackendLeaderElectorBenchmark.*' \
  -bm thrpt -tu s -f 1 -wi 1 -i 1 -w 200ms -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-524-kubernetes-scenarios-throughput.json

java -jar benchmark/build/benchmarks/kubernetesBenchmark/jars/benchmark-kubernetesBenchmark-jmh-0.5.0-JMH.jar \
  '.*KubernetesBackendLeaderElectorBenchmark.*' \
  -bm avgt -tu us -f 1 -wi 1 -i 1 -w 200ms -r 200ms \
  -rf json -rff docs/benchmarks/2026-07-02-issue-524-kubernetes-scenarios-average-time.json
```

The direct JMH jar path is used here so the short K3s smoke run can write
issue-specific JSON files. The canonical discovery/build surface remains the
`kubernetesBenchmark` Gradle target.

## Results

| Scenario | Throughput (ops/s) | Average time (us/op) | Interpretation |
|---|---:|---:|---|
| `blockingFreshAcquire` | 82.297 | 12,810.608 | Public blocking elector creates/acquires/releases a fresh Lease. |
| `blockingPreHeldSkip` | 661.149 | 1,547.237 | Public blocking elector observes an active external holder and skips. |
| `blockingExpiredTakeover` | 89.767 | 9,137.928 | Public blocking elector takes over an expired holder and releases. |
| `blockingLeaseRenewalUpdate` | 208.781 | 4,209.766 | Direct Lease API update for a same-holder renewal window. |
| `blockingResourceVersionConflict` | 539.753 | 3,039.625 | Direct stale `resourceVersion` update that returns Kubernetes 409. |
| `suspendFreshAcquire` | 90.055 | 10,753.638 | Suspend elector acquire+release path wrapped in `Dispatchers.IO`. |
| `suspendPreHeldSkip` | 465.583 | 2,690.823 | Suspend elector active-holder skip path. |
| `suspendExpiredTakeover` | 97.097 | 8,634.000 | Suspend elector expired-holder takeover path. |
| `suspendLeaseRenewalUpdate` | 258.746 | 4,720.792 | Direct Lease API renewal update from the suspend benchmark lane. |
| `suspendResourceVersionConflict` | 425.577 | 2,181.023 | Direct stale `resourceVersion` conflict from the suspend benchmark lane. |

![Kubernetes Lease scenario throughput](../images/readme-charts/leader-kubernetes-scenarios-throughput-chart-01.png)

![Kubernetes Lease scenario latency](../images/readme-charts/leader-kubernetes-scenarios-latency-chart-01.png)

## Interpretation

- The pre-held skip path is much faster than fresh acquire or expired takeover
  because it reads the active holder and returns without writing the Lease.
- Fresh acquire is slower than expired takeover in this short smoke snapshot.
  The fresh path creates the Lease before release, while takeover updates an
  existing Lease resource.
- Renewal and conflict rows are intentionally direct API probes. They help
  separate API-server update/conflict cost from local user action execution.
- Blocking and suspend rows are comparable but not identical. Suspend public
  elector calls wrap Fabric8 operations in `Dispatchers.IO`, while direct
  suspend probes use the same IO boundary around Lease API calls.
- This is a short K3s Testcontainers smoke snapshot with one fork, one thread,
  one warmup, and one 200 ms measurement iteration. Repeat with longer windows
  before using the numbers for production tuning.

## Artifacts

- Throughput JSON: [`2026-07-02-issue-524-kubernetes-scenarios-throughput.json`](2026-07-02-issue-524-kubernetes-scenarios-throughput.json)
- Average-time JSON: [`2026-07-02-issue-524-kubernetes-scenarios-average-time.json`](2026-07-02-issue-524-kubernetes-scenarios-average-time.json)
- Throughput chart: [`leader-kubernetes-scenarios-throughput-chart-01.svg`](../images/readme-charts/leader-kubernetes-scenarios-throughput-chart-01.svg) / [`leader-kubernetes-scenarios-throughput-chart-01.png`](../images/readme-charts/leader-kubernetes-scenarios-throughput-chart-01.png)
- Latency chart: [`leader-kubernetes-scenarios-latency-chart-01.svg`](../images/readme-charts/leader-kubernetes-scenarios-latency-chart-01.svg) / [`leader-kubernetes-scenarios-latency-chart-01.png`](../images/readme-charts/leader-kubernetes-scenarios-latency-chart-01.png)
