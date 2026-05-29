# Issue 418 Preview Backend Benchmark Plan

## Work Type

Fast-track performance benchmark extension. No production API change, but the
scope touches multi-backend infrastructure and documentation, so this follows a
design-grade verification path.

## Tasks

1. Inspect current benchmark harness, backend constructors, and integration-test
   Testcontainers patterns.
2. Add default preview backend dependencies to `benchmark/build.gradle.kts`.
3. Add a separate `kubernetesBenchmark` source set and runtime classpath for
   K8s.
4. Extend blocking benchmark parameters and factories for Consul, etcd, and
   DynamoDB.
5. Extend suspend benchmark parameters and factories for the same backends.
6. Add a separate K8s benchmark class with blocking and suspend rows.
7. Compile benchmark sources.
8. Run default and K8s timing benchmark tasks serially when local Docker/K3s
   resources allow it.
9. Copy or reference fresh JSON output under `docs/benchmarks/`.
10. Update `benchmark/README.md` and `benchmark/README.ko.md` with measured rows,
   caveats, and raw result path.
11. Add a lesson for future benchmark agents.
12. Run local 7-Tier code review, commit, push, create PR, watch CI, and merge
    after CI success per thread instruction.

## Validation Commands

```bash
./gradlew :benchmark:compileBenchmarkKotlin --no-daemon
./gradlew :benchmark:compileKubernetesBenchmarkKotlin --no-daemon
./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon
./gradlew :benchmark:kubernetesBenchmarkBenchmark :benchmark:kubernetesBenchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon
git diff --check
```

The timing benchmark commands are intentionally serial because they start
Testcontainers-backed services. Kubernetes is separate because its Fabric8
runtime needs Vert.x 4 / Netty 4.1 while the default preview target keeps
Vert.x 5 for etcd.

## Step 3-R Local 7-Tier Review

| Tier | Scope | P0 | P1 | P2 | P3 | Evidence |
|---|---|---:|---:|---:|---:|---|
| Security | No external credentials or network beyond local containers | 0 | 0 | 0 | 0 | DynamoDB Local uses dummy Testcontainers credentials. |
| Ops/SRE | Container startup, cleanup, and failure diagnosis | 0 | 0 | 0 | 1 | K3s can be slow and runs separately; plan requires explicit caveat if timing cannot finish. |
| Structural | Benchmark-only module boundary | 0 | 0 | 0 | 0 | No production modules are modified. |
| Kotlin/API | Blocking and suspend parity | 0 | 0 | 0 | 0 | Default preview classes cover Consul/etcd/DynamoDB; K8s class covers blocking and suspend. |
| Tests/types | Compile plus smoke checks | 0 | 0 | 0 | 0 | `compileBenchmarkKotlin` and setup smoke checks cover false rows. |
| Performance | Measurement integrity | 0 | 0 | 0 | 0 | README rows depend on fresh JSON output. |
| Docs/evidence | README, localized README, lesson | 0 | 0 | 0 | 0 | Plan includes all required artifacts. |

P0/P1: 0. Gate closed.
