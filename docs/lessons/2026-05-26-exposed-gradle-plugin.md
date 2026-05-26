## Context

Adopted the JetBrains Exposed Gradle plugin for the leader migration gate example after the shared dependency catalog added a central plugin alias.

## Decision

Library repositories should consume the plugin alias from the managed `bt4k` catalog and pin the default catalog ref to `catalog/2026-05-26-00`.

## Outcome

`examples:migration-gate` now exposes the `generateMigrations` task with explicit table package and H2 migration database settings.

## Verification

Ran `git diff --check`, `./gradlew -q help`, and `:examples:migration-gate:tasks --all`.

## Future Guard

Use Exposed plugin DSL properties `databaseUser` and `databasePassword`; `user` and `password` are not extension properties.
