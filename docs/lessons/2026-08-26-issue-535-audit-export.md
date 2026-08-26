# Issue #535 AUD-03 audit export lesson

날짜: 2026-08-26

## 배경

leader election history와 lifecycle event를 외부 sink로 전달하는 범위에서
HTTP/webhook transport를 추가했다. 선출 hot path가 네트워크 지연이나 receiver
장애를 직접 기다리지 않도록 기존 bounded exporter의 admission·retry·close 계약에
JDK `HttpClient.sendAsync` one-shot delivery만 연결했다.

## 결정

1. `leader-core`에 새 HTTP/serialization dependency를 넣지 않고
   `LeaderAuditPayloadEncoder`를 주입한다. JSON, JSONL, OpenTelemetry의 wire
   format과 수명은 호출자 또는 후속 adapter가 선택한다.
2. production target은 `LeaderAuditTrustedHttpsEndpoint`로 감싼 HTTPS URI만
   받는다. URI syntax와 credential/query/fragment 경계는 라이브러리가 검사하고,
   DNS rebinding·private network·SSRF 정책은 allow-list 또는 egress proxy를
   소유한 호출자에게 남긴다.
3. payload는 생성·읽기 양쪽에서 방어적으로 복사하고 hard 1 MiB 및 설정 가능한
   기본 64 KiB bound를 적용한다. response는 `BodyHandlers.discarding()`으로
   retention을 0 byte로 제한한다.
4. 2xx는 성공, 408/429/5xx와 I/O/timeout은 retryable, 나머지 non-2xx는
   terminal로 분류한다. `ACCEPTED`는 admission일 뿐 delivered가 아니며, retry는
   at-least-once이므로 receiver idempotency가 필요하다.
5. header는 `Content-Type`과 `Authorization`만 허용하고, redirect는
   `HttpClient.Redirect.NEVER`를 요구한다. credential, body, endpoint, exception
   message는 로그·metric·observer payload로 복사하지 않는다.

## 배운 점

- 기존 `BoundedLeaderAuditExporter`를 재사용하면 queue permit, scheduler ownership,
  close cancellation, late completion 처리를 transport 코드에서 다시 만들지 않아도 된다.
- Kotlin `internal` delivery와 public facade를 분리하면 public ABI에 bounded core
  구현 세부를 노출하지 않으면서 Java `HttpClient` 호출 경계를 테스트할 수 있다.
- `Content-Type`처럼 encoder와 header map이 동시에 표현할 수 있는 값은 현재
  encoder 우선순위를 명시했더라도 public API가 굳기 전에 단일 책임을 재검토해야 한다.
- deterministic `HttpClient` stub은 status·future·cancellation 분류를 빠르게
  고정하지만 실제 TLS handshake, timeout, body discard 상호작용을 대신하지 않는다.
- delivery 경계에서 `CancellationException`은 일반 예외와 분리해 다시 전파하고,
  비-I/O 비동기 예외는 retryable로 과대 분류하지 않아야 한다. 두 경계는 회귀
  테스트로 고정했다.

## 다음 조치

- 1.0.0 전 in-process TLS loopback smoke와 실제 cancellation/timeout 상호작용을
  추가한다.
- retry마다 재생성되는 encoder payload를 receiver가 안전하게 deduplicate할 수
  있도록 stable delivery ID 또는 별도 idempotency SPI를 설계한다.
- Spring/Ktor integration에서 caller trust wrapper를 임의 URI에 자동 적용하지
  않도록 configuration 경계를 설계한다.

## 검증

- HTTP focused suite: 10 tests PASS
- `leader-core` full test: BUILD SUCCESSFUL
- `leader-core` detekt: BUILD SUCCESSFUL
- Korean terminology audit: 6 files, findings=0
- `git diff --check`: PASS
