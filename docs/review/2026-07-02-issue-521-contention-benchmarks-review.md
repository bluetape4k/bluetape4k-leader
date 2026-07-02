# Issue #521 Contention Benchmark Review

## Scope

- `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/LeaderContentionElectorBenchmark.kt`
- `benchmark/README.md`
- `benchmark/README.ko.md`
- `docs/benchmarks/2026-07-02-issue-521-contention-*`
- `docs/images/readme-charts/leader-contention-*`
- `docs/lessons/2026-07-02-issue-521-contention-benchmarks.md`

## Review Notes

- No P0/P1 findings found in direct diff review.
- CodeGraph review context returned an empty graph for this worktree, so review
  used direct source inspection, pattern search, compile, JMH jar generation,
  and smoke benchmark runs instead.
- Held-lock setup verifies skipped results and zero action executions before
  benchmark measurement.
- Local contention is split from remote/shared factories so in-process lock
  state is shared by the benchmarked local elector instance.
- Local suspend `waitTime=0` parallel rows are intentionally absent because
  `withTimeoutOrNull(0)` can time out before meaningful contention is measured.
- `runCatching` appears only in non-suspend resource cleanup helpers.

## Verification Evidence

- `:benchmark:compileBenchmarkKotlin` passed.
- `:benchmark:benchmarkBenchmarkJar` passed.
- Local-only JMH smoke passed with `contenders=2`.
- Lettuce container-backed skip-path JMH smoke passed with `contenders=2`.
- `jq empty` passed for all four issue #521 raw JSON files.
- `xmllint --noout` passed for both chart SVG files.
- CairoSVG rendered both chart PNG files and visual inspection found no label,
  legend, or axis overlap.
- `node scripts/check-readme-language-switches.mjs` passed.
- `git diff --check` passed.

## Residual Risk

- README charts use quick no-warmup, no-fork, 100 ms JMH snapshots. They are
  regression and backend-shape evidence, not release-grade rankings.
- Exposed H2 contention rows are implemented but not included in the README
  chart snapshot to keep the chart focused on remote backends plus local
  baseline rows.
