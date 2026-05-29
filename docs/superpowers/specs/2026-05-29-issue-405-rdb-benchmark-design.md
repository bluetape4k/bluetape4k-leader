# Issue 405 RDB Benchmark Design

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
