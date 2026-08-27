---
title: "Backend connectivity observability and readiness runbook"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Backend connectivity observability and readiness runbook

> Unreleased draft for Issue #774 and the OBS-01–04 stacked train. The current
> `develop` sources contain this additive observability contract, but the
> versioned manual remains pinned to `0.5.0` at
> `721a9a3808f67489d2bdb8177734325981c24977`. Do not promote this draft or
> change `docs/manual/manifest.yaml` until a release commit contains the full
> train.

This document describes how to interpret backend connectivity diagnostics. It
does not replace the atomic ownership decision made by `runIfLeader`, and it
does not turn a best-effort health signal into a fencing or force-release
operation.

## Status and reason model

Every connectivity result has one status and one bounded reason. The reason is
an enum, not an exception message or a provider payload.

| Status | Reason | Meaning |
|---|---|---|
| `UP` | `CONNECTED` | The existing client confirmed reachability at probe time. |
| `DOWN` | `DISCONNECTED` | The existing client confirmed that the backend is unavailable. |
| `UNKNOWN` | `CLIENT_STATE_UNCONFIRMED` | A bounded read-only check could not prove connectivity. |
| `UNKNOWN` | `PROVIDER_UNSUPPORTED` | The provider does not expose a supported active probe. |
| `UNKNOWN` | `PROVIDER_EXCEPTION` | An ordinary provider exception was normalized without retaining its details. |
| `NOT_CHECKED` | `NOT_CHECKED` | No active probe was requested; this is not a health signal. |

`UP` and `DOWN` describe the result of an active check, not lock ownership.
`UNKNOWN` must remain distinguishable from both. `NOT_CHECKED` is the expected
result of passive diagnostics and must not be treated as `UP`.

The public result is additive:

```kotlin
data class LeaderBackendConnectivity(
    val status: LeaderBackendConnectivityStatus,
    val checkedAt: Instant?,
    val latencyMillis: Long?,
    val reason: LeaderBackendConnectivityReason,
)
```

Existing Kotlin construction and JSON fields remain compatible; consumers that
strictly deserialize JSON must allow the new `reason` field.

## Source and adapter mapping

| Source or adapter | Contract |
|---|---|
| Direct/core diagnostics | `diagnostics()` is passive and returns `NOT_CHECKED`; an active `checkConnectivity` returns the provider result and bounded reason. |
| Ktor `/management/leaderElection/diagnostics` | Returns HTTP 200 with the diagnostics payload when the pipeline produced a result. The payload's `connectivity.status` and `connectivity.reason` carry backend meaning. |
| Spring `leaderBackendDiagnostics` | Static diagnostics remain opt-in and passive. Backend health adds bounded `reason` detail; `UP`/`DOWN`/`UNKNOWN` retain their existing health mapping. |
| Spring readiness | The readiness indicator evaluates local lock state and lease expiry. It is a separate signal and does not automatically merge backend `DOWN` or `UNKNOWN`. |
| Application pipeline | A custom provider exception that escapes the route is owned by Ktor `StatusPages` or the application's Spring/web pipeline. The library does not rewrite that HTTP status. |

Ktor returning HTTP 200 means that a diagnostics result was serialized; it does
not mean that the backend is healthy. A pipeline exception is different from a
payload containing `status = UNKNOWN`.

## Micrometer and Prometheus

An instrumented elector records one `leader.backend.connectivity` counter event
for each active `checkConnectivity` or `diagnostics(probe = true)` call. The
counter is not a background poller. Passive `diagnostics()` calls do not create
a series.

| Tag | Allowed values and protection |
|---|---|
| `backend.name` | The sanitized descriptor backend ID; never an endpoint, credential, tenant, or lock name. |
| `status` | `UP`, `DOWN`, `UNKNOWN`, or `NOT_CHECKED`. |
| `reason` | The six `LeaderBackendConnectivityReason` enum names. |

Registry naming converts the source meter to
`leader_backend_connectivity_total` in Prometheus. No exception class,
message, endpoint, credential, raw provider payload, or lock name is exported.
Keep active probe frequency bounded in the caller's scheduler. A counter event
is an observation, not a retry instruction.

For the bundled dashboard, the following queries are intentionally
warning-oriented and low-cardinality:

```promql
sum by (backend_name, status, reason) (rate(leader_backend_connectivity_total[5m]))
sum by (backend_name) (
  rate(leader_backend_connectivity_total{status="DOWN",reason="DISCONNECTED"}[5m])
)
sum by (backend_name, reason) (
  rate(leader_backend_connectivity_total{status="UNKNOWN"}[5m])
)
```

The example's `LeaderBackendConnectivityDown` rule requires five minutes and
is `notification: no-page`. The `UNKNOWN` and
`PROVIDER_EXCEPTION` rules also require sustained observations and never
promote `UNKNOWN` to `DOWN` automatically.

## Provider and #766 compatibility boundary

Built-in providers use the public `LeaderBackendDiagnosticsProbe.check` helper.
It validates a positive, finite provider-native timeout, reads the clock once
before the callback, maps an ordinary `Exception` to
`UNKNOWN + PROVIDER_EXCEPTION`, and rethrows cancellation, restored
interruption, and fatal `Error` values. A callback returning `NOT_CHECKED` is
invalid for an active probe.

The helper's `unknownReason` distinguishes a provider that is unsupported from
one whose client state cannot be confirmed. Existing custom
`checkConnectivity` or `diagnostics` overrides remain an escape hatch: they own
their provider exception behavior, while Micrometer observes the result without
changing it. Legacy providers that still use their manual diagnostics boundary
keep the compatible default reason until a separate provider-migration issue
updates them. This draft records that boundary; it does not silently expand
#766's implementation scope.

## Timeout and wall-clock runbook

The `timeout` passed to `LeaderBackendDiagnosticsProbe.check` is a
provider-native budget. It is validated and passed to the callback, but it is
not a caller-thread wall-clock deadline. A client that ignores the budget or
does not support cancellation can keep the calling thread waiting.

When a probe is slow or uncertain:

1. For repeated `UNKNOWN + CLIENT_STATE_UNCONFIRMED`, inspect the existing
   client lifecycle and its native timeout settings first.
2. For increasing `UNKNOWN + PROVIDER_EXCEPTION`, inspect protected structured
   application logs and provider-native diagnostics. Do not copy exception text
   into metrics or route details.
3. If a hard request deadline is required, own an executor/future timeout or a
   backend cancellation API in the application. Do not add an interrupt or
   forced `Future.cancel` to the library helper.
4. If active probing exceeds the request budget, disable the active probe and
   use passive diagnostics plus the existing state/readiness signal until the
   provider is corrected.

### Bypass and rollback

Disable `bluetape4k.leader.observability.backend-health` or
`backendConnectivityCheckEnabled` to stop active calls. Remove or protect the
Ktor/Spring management route as appropriate. A missing counter series means
that no active probe ran; it is not a synthetic `NOT_CHECKED` health sample.

Before rolling back one train child, verify that JSON consumers ignore an
additive `reason` field and that dashboards tolerate the counter disappearing.
Revert one child at a time; do not delete a public field or meter name without
a deprecation and consumer-migration window. Never force-release a lease from a
connectivity result. Re-check ownership through the backend's conditional
semantics and continue using `runIfLeader` for execution.

## Operational checklist

- [ ] The route is authenticated and restricted by network policy.
- [ ] Passive diagnostics is not used as readiness proof.
- [ ] Active probe frequency and caller wall-clock deadline are explicit.
- [ ] `backend.name`, `status`, and `reason` are the only exported tags.
- [ ] `UNKNOWN` is warning/no-page unless an application policy says otherwise.
- [ ] Provider-native timeout and cancellation behavior are documented for the
      selected backend.
- [ ] Built-in and custom providers are tested at the direct, Ktor, and Spring
      boundaries.
- [ ] The versioned manual is promoted only after `releaseRef` and
      `releaseCommit` point at a commit containing this contract.

See the development-line source descriptions in the [root diagnostics
section](../../../README.md#runtime-backend-diagnostics), the
[Prometheus dashboard runbook](../../../examples/prometheus-dashboard/README.md#alert-runbooks),
and the [backend selection guide](../en/guides/backend-selection.md).
