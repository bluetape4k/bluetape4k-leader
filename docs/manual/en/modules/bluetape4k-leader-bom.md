---
manualId: "bluetape4k-leader-bom"
id: "bluetape4k-leader-bom"
title: "Leader BOM"
locale: "en"
kind: "library"
gradlePath: ":bluetape4k-leader-bom"
sourceDir: "bluetape4k-leader-bom"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-bom
---

# Leader BOM

> Library module

## Problem {#problem}

Aligns every published Leader artifact. Applications should not choose this internal BOM separately; `bluetape4k-dependencies` already imports it.

## When to use it {#when-to-use}

Use this page to diagnose version alignment. For normal setup, choose one ecosystem platform version and omit versions from all Leader modules.

## Coordinates {#coordinates}

Internal coordinate: `io.github.bluetape4k.leader:bluetape4k-leader-bom`. Consumers import `io.github.bluetape4k:bluetape4k-dependencies:<version>`.

## Core concepts {#concepts}

This is a Gradle `java-platform`: it publishes constraints, not runtime classes. Ownership flows from the application to `bluetape4k-dependencies`, then to this BOM.

## Quick start {#quick-start}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-core")
}
```

## API by task {#api-by-task}

There is no runtime API. Use `dependencyInsight` to inspect which platform selected a module version.

## Recommended patterns {#patterns}

Import one top-level platform and keep module declarations versionless. Upgrade the platform as one reviewed change.

## Integrations {#integrations}

The consumer-facing integration is `bluetape4k-dependencies`; Maven users import it through dependency management.

## Configuration {#configuration}

The BOM has no properties, beans, or lifecycle. Stable artifacts resolve from Maven Central.

## Failure modes {#failures}

Mixing a direct Leader BOM with explicitly versioned modules splits version ownership. Remove module versions and inspect the resolved constraints.

## Operations {#operations}

Record the selected `bluetape4k-dependencies` version in build and deployment metadata, not an internal Leader BOM version.

## Testing {#testing}

Compile one Core type and one selected backend after a platform upgrade; dependency locking can guard the resolved graph.

## Workshops and learning path {#workshops}

Start with the repository overview, then open the backend you will operate. Examples demonstrate executable combinations.

## Limitations {#limitations}

Version alignment does not guarantee compatibility with every external Redis, database, Kubernetes, or framework version.

<!-- release-readme-diagrams:start -->
## Release diagrams {#release-diagrams}

These diagrams are loaded directly from README assets published with the `0.5.0` release and pinned to its immutable commit. They describe this manual's released structure and runtime flows, not later Snapshot changes. Select a preview to open the SVG at the same release commit.

### leader bom Architecture diagram

[![leader bom Architecture diagram](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-bom-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/bluetape4k-leader-bom-architecture-01.svg)

_Release README: [`bluetape4k-leader-bom/README.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/bluetape4k-leader-bom/README.md)_

<!-- release-readme-diagrams:end -->

## Sources {#sources}

[Stable BOM build](../../../../bluetape4k-leader-bom/build.gradle.kts) · [Stable repository guide](../../../../README.md)

