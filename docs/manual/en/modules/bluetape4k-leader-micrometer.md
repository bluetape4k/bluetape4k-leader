---
manualId: "bluetape4k-leader-micrometer"
id: "bluetape4k-leader-micrometer"
title: "Micrometer instrumentation"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-micrometer"
sourceDir: "leader-micrometer"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-micrometer
---

# Micrometer instrumentation

> Library module

## Problem {#problem}

Adds Micrometer metrics around single/group/suspend electors, Core lifecycle listeners, Spring AOP recording, and history sinks without changing election truth.

## When to use it {#when-to-use}

Use it when operations need acquisition, skip, failure, duration, or history metrics across backends. Keep it out if no registry consumes the data.

## Coordinates {#coordinates}

Artifact: `io.github.bluetape4k.leader:bluetape4k-leader-micrometer`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer")
}
```

## Core concepts {#concepts}

Instrumented wrappers delegate ownership to the underlying elector. Metrics describe attempts and outcomes; they never grant permission to execute.

## Quick start {#quick-start}

```kotlin
val instrumented = InstrumentedLeaderElector(
    delegate = elector,
    registry = meterRegistry
)
instrumented.runIfLeader("daily-report") { generateReport() }
```

## API by task {#api-by-task}

Wrap direct electors, attach `MicrometerLeaderElectionListener`, use the AOP recorder for Spring, or decorate safe history recorders.

## Recommended patterns {#patterns}

Register one wrapper per logical elector, use bounded tags, and keep lock names out of tags when they are tenant- or request-derived.

## Integrations {#integrations}

Works with every Core-compatible backend. prometheus-dashboard demonstrates export and dashboard/alert wiring.

## Configuration {#configuration}

Choose registry, common tags, meter filters, histogram/percentile policy, and cardinality limits in the application. The module does not configure a monitoring backend.

## Failure modes {#failures}

A metrics registry failure must not become ownership truth. Duplicate wrapping double-counts. Unbounded lock-name tags can exhaust memory even when election works.

## Operations {#operations}

Alert on backend failure and extension failure separately from normal skip. Track duration and attempt volume, then correlate with backend latency.

## Testing {#testing}

Use a simple registry to assert names/tags/outcomes, failure paths, suspend cancellation, no-op behavior, and history decoration without duplicate counts.

## Workshops and learning path {#workshops}

Run prometheus-dashboard after learning Core outcomes. Then use the chosen backend page to interpret which failures are operationally actionable.

## Limitations {#limitations}

Metrics are observational and may be dropped. They do not provide durable audit, tracing, alerts, dashboards, or low-cardinality policy automatically.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.4.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader-micrometer instrumentation architecture diagram

[![leader-micrometer instrumentation architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-micrometer-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-micrometer-architecture-01.svg)

_Release README: [`leader-micrometer/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-micrometer/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Instrumented electors](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/InstrumentedLeaderElectors.kt) · [Listener](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt) · [Stable guide](../../../../leader-micrometer/README.md)

