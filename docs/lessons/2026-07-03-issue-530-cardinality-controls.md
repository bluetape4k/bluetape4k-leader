# Issue #530 Lessons

## Context

Issue #530 added cardinality controls for leader Micrometer tags. The change touched direct meter recorders, Observation integration, Spring Boot property binding, Prometheus dashboard docs, and README locale pairs.

## Decisions

- Treat dynamic `lock.name` and opt-in `leader.id` as sensitive high-cardinality values by default.
- Keep `RAW` opt-in available only for small static sets, and document that `HASH` is deterministic unsalted pseudonymization, not cardinality reduction.
- Apply the same tag policy to Micrometer meters and Observation high-cardinality fields.
- Preserve binary-compatible constructor entry points when adding data-class properties used by public Kotlin/JVM callers.

## Outcome

- Default exports collapse dynamic lock and leader identifiers to redacted sentinels.
- Spring Boot exposes nested tag policy properties and passes them to both meter and Observation auto-configurations.
- Registration and deregistration now handle collapsed tags without prematurely removing active meters.
- README EN/KO, module docs, metadata, and dashboard PromQL were updated together.

## Future Guard

- When adding observability options, verify every emission path: direct meters, decorators, event listeners, Observation recorders, Spring auto-config, metadata, dashboard examples, and README locale pairs.
- For public Kotlin data classes, run `javap` after constructor changes and preserve prior JVM entry points when compatibility matters.
- For concurrency-sensitive meter registration, use `MultithreadingTester` rather than ad hoc thread loops and record the helper in review evidence.
- If a tag policy exists for future or custom emitters, state whether current built-in emitters actually produce that tag.

## Verification Evidence

- `./gradlew :bluetape4k-leader-micrometer:test :bluetape4k-leader-spring-boot:test :examples:prometheus-dashboard:test`
  - PASS, 349 passing, `BUILD SUCCESSFUL in 1m 36s`.
- Step 6-R final blocking count: P0=0, P1=0.
