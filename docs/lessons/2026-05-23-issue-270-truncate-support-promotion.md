# 2026-05-23 Issue 270 Truncate Support Promotion

## Context

`bluetape4k-projects 1.9.1` promoted `String.truncateUtf8` to
`io.bluetape4k.support`. `bluetape4k-leader` issue #270 tracks removal of the
local internal truncation helper after that upstream release.

## Decision

Use `io.bluetape4k.support.truncateUtf8` from `bluetape4k-core` and delete the
local `leader.internal.StringTruncateSupport` copy.

The leader build must reference the newly published `bluetape4k-projects`
release version directly. `catalogVersion` is for external library/plugin
alignment across bluetape4k repositories, not for replacing internal
`bluetape4k-*` release ordering.

## Outcome

`leader-core` history sanitization now depends on the shared support function.
The local duplicate helper was removed.

## Verification

```bash
./gradlew :bluetape4k-leader-core:test \
  --tests 'io.bluetape4k.leader.history.LeaderHistoryRecorderSupportTest' \
  -Pbluetape4kCatalogVersion=2026-05-23-00-SNAPSHOT \
  --refresh-dependencies --no-daemon --no-configuration-cache --no-build-cache
```

Result: `BUILD SUCCESSFUL`, 15 tests passing.

## Future Guard

When migrating bluetape4k repositories to the shared catalog, do not move
internal `bluetape4k-*` release versions behind `catalogVersion`. Check the
local BOM/version declarations and bump them only after the upstream release is
visible from Maven Central.
