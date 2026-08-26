---
title: "Audit export to an HTTP/webhook sink"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Audit export to an HTTP/webhook sink

> Unreleased draft for Issue #535 (OBS-03). The HTTP adapter is newer than the
> pinned `0.5.0` manual and must not be presented as part of that release until
> the release manifest is updated.

## What this adds

`leader-core` can deliver sanitized `LeaderAuditExportEvent` values to a webhook
without coupling leader election to network latency. `HttpLeaderAuditExporter`
uses JDK `java.net.http.HttpClient`; the application supplies the payload encoder,
executor, scheduler, endpoint trust decision, and receiver idempotency policy.

The exporter reuses the bounded core pipeline. `submit` is non-blocking admission:
`ACCEPTED` means that the event entered the bounded pipeline, not that the receiver
accepted the HTTP request.

## Prerequisites

- JDK 21 or newer, as required by the leader project.
- `io.github.bluetape4k.leader:bluetape4k-leader-core`.
- An HTTPS receiver and an application-owned allow-list or egress proxy.
- A serializer if the receiver expects JSON. `leader-core` adds no JSON dependency.

## Minimal composition

```kotlin
val scheduler = Executors.newSingleThreadScheduledExecutor()
val executor = Executors.newVirtualThreadPerTaskExecutor()
val endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(
    URI("https://audit.example.test/v1/leader-events"),
)
val exporter = HttpLeaderAuditExporter(
    client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    endpoint = endpoint,
    headers = mapOf("Authorization" to "Bearer ${System.getenv("AUDIT_WEBHOOK_TOKEN")}"),
    encoder = LeaderAuditPayloadEncoder { event ->
        LeaderAuditHttpPayload.of(
            contentType = "text/plain; charset=utf-8",
            body = event.toString().toByteArray(),
        )
    },
    exportOptions = LeaderAuditExportOptions(
        queueCapacity = 256,
        maxInFlight = 8,
        maxAttempts = 3,
        attemptTimeout = Duration.ofSeconds(5),
        initialBackoff = Duration.ofMillis(100),
        maxBackoff = Duration.ofSeconds(5),
        executor = executor,
        scheduler = scheduler,
    ),
    httpOptions = LeaderAuditHttpOptions.defaults(),
)

try {
    val event = /* create a sanitized History or Lifecycle event */
    exporter.submit(event)
} finally {
    exporter.close()
    executor.close()
    scheduler.shutdown()
}
```

Wrap the exporter with `MicrometerLeaderAuditExporter` when queue depth, admission,
retry, failure, and cancellation counters should be exported. Register an observer
with `observe` when an application needs fixed low-cardinality lifecycle outcomes
without owning another exporter.

## HTTP contract

The adapter creates one `POST` request per delivery attempt and applies the
`attemptTimeout` from `LeaderAuditExportOptions`. A payload is copied on creation and
again when read through `body()`. The default configured payload limit is 64 KiB; the
absolute limit is 1 MiB. A payload over the configured limit is terminalized before a
request is created.

Only `Content-Type` and `Authorization` can be supplied as headers. Names and values
reject control characters; `Host`, `Content-Length`, `Connection`,
`Transfer-Encoding`, and all unknown headers are rejected. Response bodies use
`BodyHandlers.discarding()`: this bounds response retention in the adapter but does not
truncate bytes while they are arriving on the network.

| Response or failure | Delivery result | Bounded pipeline action |
|---|---|---|
| 2xx | `SUCCESS` | Finish the item |
| 408, 429, 5xx | `RETRYABLE_FAILURE` | Retry until `maxAttempts` |
| Other non-2xx | `TERMINAL_FAILURE` | Do not retry |
| I/O or timeout failure | `RETRYABLE_FAILURE` | Retry until `maxAttempts` |
| Encoder/request validation failure | `TERMINAL_FAILURE` | Do not create a request |

Retries are at-least-once. The receiver must use an idempotency key or equivalent
deduplication rule when duplicate deliveries are unsafe.

## Endpoint trust and privacy

`LeaderAuditTrustedHttpsEndpoint.trusted(uri)` accepts only an absolute hierarchical
HTTPS URI without user-info, query, fragment, or control characters. This is an
explicit responsibility boundary, not a DNS or SSRF firewall. The caller owns endpoint
allow-listing, DNS rebinding decisions, and private/link-local/ULA/CGNAT policy; use a
static trusted target or an egress proxy for those controls.

The adapter never logs the endpoint, authorization value, payload body, or exception
message. Sanitized events must be created before they reach the encoder. JSONL files,
OpenTelemetry, durable outbox, and framework auto-configuration are separate follow-up
work.

## Shutdown and diagnosis

Call `HttpLeaderAuditExporter.close()` before shutting down the caller-owned executor
or scheduler. Close cancels queued, scheduled, and in-flight work and prevents a late
HTTP completion from scheduling another retry. The exporter does not shut down either
executor. Inspect `snapshot()` and the fixed Micrometer catalog for queue saturation,
retries, terminal failures, cancellations, and rejection counts; these values contain
no dynamic lock, identity, endpoint, or error fields.

If delivery is repeatedly retryable, check receiver availability and timeout budget.
If responses are terminal 4xx, fix the payload or authorization policy rather than
blindly retrying. If the endpoint is rejected at construction, correct the explicit
HTTPS URI or caller trust configuration. A successful `submit` alone is not evidence
that the receiver has processed an event.

## Next learning step

Read the core observability and failure guides, then compare the Micrometer decorator
with the framework-neutral observer. JSONL and OpenTelemetry transports remain
follow-up designs rather than implicit behavior of this adapter.
