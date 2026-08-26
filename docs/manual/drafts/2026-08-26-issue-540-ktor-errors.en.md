---
title: "Ktor structured error contract"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor structured error contract

> Unreleased draft for Issue #540 and Epic #701. Keep the release-pinned manual limited to APIs present at `sourceReleaseCommit`.

Management and optional `StatusPages` handling use the same converter-free JSON contract.
Normal lock contention remains `null`/skip and is not an HTTP error.

| Code | HTTP status | Meaning |
|---|---:|---|
| `INVALID_LOCK_NAME` | 400 | Blank or malformed core lock name |
| `NOT_LEADER` | 503 | Current leader state rejects the request |
| `LEADER_LOCKED` | 423 | Leader lock is already held |
| `BACKEND_UNAVAILABLE` | 503 | Backend/state access failed |
| `CONFIGURATION`, `INTERNAL` | 500 | Configuration or unexpected request failure |
| `INVALID_CURSOR` | 400 | Malformed stream cursor |

Responses contain only `code`, `message`, and numeric `status` unless a typed
`LeaderElectionErrorOverride` explicitly enables `lockName`. Backend messages, stack traces,
and cause details are logged only as a sanitized cause type and never copied into the response.
`CancellationException` is rethrown.

The management route writes this JSON with `respondText`, so it works without
`ContentNegotiation`. Applications that use Ktor `StatusPages` may install the optional
adapter explicitly:

```kotlin
import io.bluetape4k.leader.ktor.statuspages.leaderElectionErrors
import io.ktor.server.plugins.statuspages.StatusPages

install(StatusPages) {
    leaderElectionErrors()
}
```

The adapter is `compileOnly`; applications that enable it must provide the matching Ktor
`ktor-server-status-pages` artifact. Detached `leaderScheduled` exceptions are logged at
`WARN`, the failed iteration is skipped, and the next cycle continues.
