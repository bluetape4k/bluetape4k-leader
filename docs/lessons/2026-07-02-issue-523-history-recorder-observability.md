# Lesson - Issue #523 History Recorder Observability Benchmarks

## Context

Issue #523 required Micrometer and terminal-state overhead coverage for leader
history recorders. The benchmark needed to stay inside the existing benchmark
module and avoid implying backend or Spring advice overhead.

## Decision

Extend `HistoryRecorderBenchmark` with a JMH `metadataMode` parameter and add
explicit no-op, in-memory, and Micrometer rows for completed and failed terminal
states. Use `SimpleMeterRegistry` so the Micrometer rows measure local counter
decoration, not exporter or external backend I/O.

## Outcome

The result table made the main cost visible: metadata sanitization dominates
large metadata rows, while Micrometer counter decoration stays close to the
in-memory sink for small metadata. Failed rows are slower because
`recordFailed` extracts and sanitizes exception details.

## Future Guard

When adding benchmark observability rows, document the measurement boundary
explicitly. If the benchmark is recorder-only, say so in the README and report,
and point readers to separate advice/backend/contention benchmarks for the
other costs.
