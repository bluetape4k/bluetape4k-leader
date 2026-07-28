# Issue 405 RDB Benchmark Design

## 한국어 해설

이 문서는 `Issue 405 RDB Benchmark Design`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Context

Issue #405 closes the benchmark gap for SQL-backed leader election rows. H2
remains useful as a local Exposed shape check, but it is not a distributed
backend measurement.

## Decision

- Add PostgreSQL and MySQL rows to the default `benchmark` source set.
- Use Exposed JDBC for blocking API rows.
- Use Exposed R2DBC for suspend API rows.
- Start PostgreSQL and MySQL through `bluetape4k-testcontainers` launcher
  singletons, matching repository test infrastructure.
- Keep Kubernetes in `kubernetesBenchmark` because Fabric8 needs the Vert.x 4
  runtime classpath.
- Rename public benchmark charts from remote backend to distributed backend.

## Acceptance

- `:benchmark:compileBenchmarkKotlin` compiles with the new rows.
- Full `:benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark`
  can run locally with Testcontainers.
- Raw JSON is committed under `docs/benchmarks/`.
- README tables and PNG charts include PostgreSQL/MySQL rows and exclude H2
  from distributed backend charts.
