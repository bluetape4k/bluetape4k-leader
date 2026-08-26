---
title: "HTTP/webhook sink으로 audit export하기"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# HTTP/webhook sink으로 audit export하기

> Issue #535(OBS-03)를 위한 미배포 초안입니다. HTTP adapter는 고정한 `0.5.0`
> 매뉴얼보다 새 API이므로 release manifest를 갱신하기 전에는 해당 배포본의
> 기능으로 안내하지 않습니다.

## 추가되는 기능

`leader-core`는 정제한 `LeaderAuditExportEvent`를 네트워크 지연과 leader
election을 결합하지 않고 webhook으로 전달할 수 있습니다.
`HttpLeaderAuditExporter`는 JDK `java.net.http.HttpClient`를 사용하며 payload
encoder, executor, scheduler, endpoint trust 결정, 수신 서버의 idempotency 정책은
애플리케이션이 제공합니다.

exporter는 bounded core pipeline을 재사용합니다. `submit`은 non-blocking
admission이므로 `ACCEPTED`는 event가 bounded pipeline에 들어갔다는 뜻이지 수신
서버가 HTTP 요청을 처리했다는 뜻이 아닙니다.

## 사전 조건

- 프로젝트가 요구하는 JDK 21 이상.
- `io.github.bluetape4k.leader:bluetape4k-leader-core`.
- HTTPS 수신 서버와 애플리케이션이 관리하는 allow-list 또는 egress proxy.
- 수신 서버가 JSON을 요구한다면 serializer. `leader-core`는 JSON 의존성을 추가하지 않습니다.

## 최소 조합

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
    val event = /* 정제한 History 또는 Lifecycle event 생성 */
    exporter.submit(event)
} finally {
    exporter.close()
    executor.close()
    scheduler.shutdown()
}
```

queue depth, admission, retry, failure, cancellation counter를 export하려면
`MicrometerLeaderAuditExporter`로 감쌉니다. 별도 exporter를 소유하지 않고 고정된
low-cardinality lifecycle 결과만 필요하면 `observe`에 observer를 등록합니다.

## HTTP 계약

adapter는 delivery attempt마다 `POST` 요청 하나를 만들고
`LeaderAuditExportOptions.attemptTimeout`을 적용합니다. payload는 생성할 때 복사하고
`body()`로 읽을 때도 복사합니다. 기본 payload limit은 64 KiB이고 절대 상한은 1 MiB입니다.
설정한 limit을 넘은 payload는 요청을 만들기 전에 terminal 처리합니다.

header로 전달할 수 있는 이름은 `Content-Type`과 `Authorization`뿐입니다. 이름과
값에는 control character를 넣을 수 없으며 `Host`, `Content-Length`, `Connection`,
`Transfer-Encoding`과 모든 미지의 header는 거부합니다. 응답 body는
`BodyHandlers.discarding()`으로 처리합니다. 따라서 adapter가 응답 body를 보관하지는
않지만 네트워크로 들어오는 byte를 중간에 자른다는 뜻은 아닙니다.

| 응답 또는 실패 | Delivery 결과 | Bounded pipeline 동작 |
|---|---|---|
| 2xx | `SUCCESS` | 항목 종료 |
| 408, 429, 5xx | `RETRYABLE_FAILURE` | `maxAttempts`까지 재시도 |
| 그 밖의 non-2xx | `TERMINAL_FAILURE` | 재시도하지 않음 |
| I/O 또는 timeout 실패 | `RETRYABLE_FAILURE` | `maxAttempts`까지 재시도 |
| Encoder/request 검증 실패 | `TERMINAL_FAILURE` | 요청을 만들지 않음 |

재시도는 at-least-once 전달입니다. 중복 전달이 안전하지 않다면 수신 서버가
idempotency key 또는 동등한 중복 제거 규칙을 제공해야 합니다.

## Endpoint trust와 privacy

`LeaderAuditTrustedHttpsEndpoint.trusted(uri)`는 user-info, query, fragment,
control character가 없는 absolute hierarchical HTTPS URI만 받습니다. 이는 DNS나
SSRF firewall이 아니라 명시적인 책임 경계입니다. endpoint allow-list, DNS rebinding
판단, private/link-local/ULA/CGNAT 정책은 호출자가 소유합니다. 이런 통제가 필요하면
고정된 trusted target이나 egress proxy를 사용하세요.

adapter는 endpoint, Authorization 값, payload body, exception message를 로그에
남기지 않습니다. encoder에 전달하는 event도 먼저 정제해야 합니다. JSONL 파일,
OpenTelemetry, durable outbox, framework auto-configuration은 별도 후속 작업입니다.

## 종료와 진단

호출자가 소유한 executor나 scheduler를 종료하기 전에
`HttpLeaderAuditExporter.close()`를 호출하세요. close는 queued, scheduled,
in-flight 작업을 취소하고 늦게 도착한 HTTP 완료가 새 retry를 예약하지 못하게 합니다.
exporter는 executor를 종료하지 않습니다. `snapshot()`과 고정된 Micrometer catalog로
queue 포화, retry, terminal failure, cancellation, rejection을 확인하세요. 이 값에는
동적인 lock, identity, endpoint, error field가 없습니다.

Delivery가 계속 retryable이면 수신 서버 상태와 timeout 예산을 확인합니다. terminal
4xx라면 무조건 재시도하지 말고 payload 또는 authorization 정책을 수정합니다.
생성 단계에서 endpoint가 거부되면 명시한 HTTPS URI와 호출자 trust 설정을 고칩니다.
`submit` 성공만으로 수신 서버가 event를 처리했다는 근거를 만들 수 없습니다.

## 다음 학습 단계

core observability와 failure guide를 읽은 뒤 Micrometer decorator와
framework-neutral observer를 비교하세요. JSONL과 OpenTelemetry transport는 이
adapter가 암묵적으로 제공하지 않는 후속 설계입니다.
