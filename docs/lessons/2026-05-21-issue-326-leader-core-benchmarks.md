# Issue #326 leader-core benchmarks

## Context

`bluetape4k-leader` needed a benchmark baseline before self-improve work. The
repo had no JMH setup, while the history audit spec already required evidence
that in-memory recorder overhead stays below the 1 ms hot-path target.

## Decision

Added a small `buildSrc` JMH convention using `me.champeau.jmh` 0.7.3 and
applied it only to `leader-core`. The first benchmarks cover local elector
execution models and history recorder wrapper overhead. Cross-backend
Testcontainers benchmarks remain separate follow-up work.

## Outcome

`compileJmhKotlin`, `jmhRunBytecodeGenerator`, and `jmh` all pass on Gradle
9.5.1 with Kotlin 2.3. The generated report is under
`leader-core/build/reports/jmh/`, and the durable baseline is in
`docs/benchmarks/2026-05-21-leader-core-baseline.md`.

## Verification

- `./gradlew :bluetape4k-leader-core:compileJmhKotlin --no-configuration-cache`
- `./gradlew :bluetape4k-leader-core:jmhRunBytecodeGenerator --no-configuration-cache`
- `./gradlew :bluetape4k-leader-core:jmh --no-configuration-cache`
- `./gradlew :bluetape4k-leader-core:test --no-configuration-cache`
- `codex review --uncommitted`

## Future Guidance

- Do not use `waitTime = 0.seconds` for coroutine elector benchmarks; it can
  measure the `withTimeoutOrNull(0)` skip path instead of the elected path.
- Document `runBlocking` and virtual-thread scheduling caveats next to every
  benchmark chart that compares execution models.
- Keep README charts out of the harness PR; add them only when backend data is
  comparable.
