# Snapshot Validation Line

## Context

After the previous release, snapshot validation needed the repository to follow
the workspace rule that `snapshotVersion=` remains empty in git while internal
references consume matching snapshots.

## Decision

Keep `baseVersion=0.2.2`, clear `snapshotVersion=`, and consume
`bluetape4k-exposed-bom:1.9.2-SNAPSHOT` alongside the existing
`bluetape4k-bom:1.9.2-SNAPSHOT`.

## Outcome

The repository can publish `0.2.2-SNAPSHOT` through `publish-snapshot.yml`
without checking a snapshot suffix into `gradle.properties`.

## Verification

Pending in the snapshot validation train.
