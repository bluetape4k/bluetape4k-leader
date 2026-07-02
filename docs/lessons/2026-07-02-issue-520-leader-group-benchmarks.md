# Issue 520 Leader Group Benchmark Lessons

## Context

Issue #520 needed group-semaphore benchmark coverage and README-visible chart
evidence. The benchmark result scale spans local in-process rows, blocking H2
rows, and remote Testcontainers-backed rows.

## Decision

Keep local and blocking H2 rows in the raw JSON and report table, but omit them
from the README charts. Use log-scale remote-backend charts so free-slot,
mixed-slot, and saturated-skip rows remain visible in one image.

## Outcome

Added blocking and suspend `LeaderGroupElectorBenchmark` suites with
`maxLeaders` parameters and charted the `maxLeaders=2` snapshot. The charts are
SVG+PNG assets under `docs/images/readme-charts/`, with raw JMH JSON preserved
under `docs/benchmarks/`.

## Future Guidance

For future benchmark chart work, record whether the chart is a quick smoke
snapshot or a release-grade run. If local rows differ by orders of magnitude,
chart remote rows separately and keep the omitted rows in the report table.
