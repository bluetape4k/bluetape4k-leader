# Issue #535 AUD-03 HTTP audit export 구현 검토

## 검토 범위와 판정

- 대상: `leader-core` JDK HTTP/webhook audit export adapter와 호출자 문서
- 이슈: #535 `feat: leader event용 pluggable audit export adapter 추가`
- Train: OBS-03, 선행 AUD-01/AUD-02 머지 head `837bea23e93f813406a6f41ba5374d783abcc979`
- 검토 기준: 승인된 Issue #535 설계·계획, 현재 working-tree diff, `leader-core` 기존 bounded exporter 계약
- 최종 로컬 판정: **WATCH (PR 생성 가능)**
- 심각도: **P0=0, P1=0, P2=3, P3=1**

P0/P1 merge 차단 결함은 확인되지 않았다. P2는 공개 API가 굳기 전 보강할
후속 위험으로 남겼으며, 이번 범위에서 새 serialization dependency, JSONL/
OpenTelemetry transport, framework auto-configuration을 추가하지 않았다.

초기 독립 code-review에서 제기된 P2 5건 중 취소 예외 전파와 비-I/O 비동기
예외 분류는 구현과 회귀 테스트로 보강했다. 최신 재검토 결과에는 아래의
idempotency, Content-Type 책임 중복, 실제 TLS 통합 검증 공백만 남겼다.

## 변경 경계

이번 변경은 다음 18개 파일에 한정된다.

- `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/`: payload encoder,
  bounded options, trusted HTTPS endpoint, JDK `HttpClient` delivery, exporter facade
- `leader-core/src/test/kotlin/io/bluetape4k/leader/audit/http/`: delivery와 lifecycle 계약 테스트
- 루트·`leader-core`·`leader-micrometer` README의 EN/KO 호출자 예제
- release-pinned manual에 영향을 주지 않는 `docs/manual/drafts/` EN/KO 초안
- 본 리뷰와 `docs/lessons/2026-08-26-issue-535-audit-export.md`

settings, BOM, 의존성, CI/Nightly, backend election 경로는 변경하지 않았다.

## 7관점 검토 결과

| 관점 | 결과 | 근거와 조치 |
|---|---|---|
| 정확성 | PASS | 2xx 성공, 408/429/5xx·I/O/timeout retryable, 그 밖의 non-2xx terminal 분류를 `HttpLeaderAuditDelivery`와 테스트로 고정했다. |
| API·계약 | PASS | 공개 생성자는 계획의 6개 인자를 유지하고, payload/options/endpoint는 fail-fast·방어적 복사를 제공한다. 기존 `LeaderAuditExporter` surface는 delegate로 재사용한다. |
| 동시성·취소 | PASS | `sendAsync`만 사용하며 반환 future 취소를 underlying future에 전달한다. bounded exporter의 close가 in-flight와 retry를 취소하고 late completion을 무시하는 테스트가 통과했다. |
| 보안·관찰성 | PASS | HTTPS-only trusted wrapper, redirect `NEVER`, header allow-list와 CR/LF·forbidden header 거부, response body discard, secret/body/error 비로깅을 고정했다. DNS/SSRF는 caller-owned 경계로 명시했다. |
| 성능·안정성 | PASS | request body와 response body retention이 bounded되고, `submit`은 기존 non-blocking bounded admission을 호출한다. HTTP 소스에서 `runBlocking`, `GlobalScope`, `Thread.sleep`, `delay`, `synchronized`, `@Synchronized`를 찾지 못했다. |
| 테스트·검증 | PASS | focused HTTP 10 tests, 전체 `leader-core` test, `leader-core` detekt, diff/용어 감사를 fresh output으로 확인했다. 실제 외부 receiver는 호출하지 않았다. |
| 문서·운영 | PASS | 루트/모듈 README와 manual draft의 EN/KO parity, executor·scheduler close 순서, idempotency·trust 책임을 기술했다. release manifest의 고정 0.5.0 manual은 변경하지 않았다. |

독립 검토 lane도 같은 diff를 read-only로 확인했다. 아키텍처 lane은
`P0=0/P1=0/P2=3/P3=1`, `WATCH`를 보고했고, code-review lane은 보안·API·Kotlin·
테스트·성능 관점의 최종 결과를 별도로 제공했다. 1인 개발자 정책에 따라
human reviewer 대기나 승인을 추가하지 않았다.

## 요구사항 추적성

| 요구사항 | 구현 근거 | 검증 |
|---|---|---|
| serialization dependency 없이 payload encoder 주입 | `LeaderAuditPayloadEncoder`, `LeaderAuditHttpPayload` | `HttpLeaderAuditDeliveryTest` encoder/body 계약 |
| payload hard 1 MiB·설정 bound·방어적 복사 | `LeaderAuditHttpOptions`, `LeaderAuditHttpPayload.of/body` | oversized, lower-bound, aliasing negative test |
| 명시적 trusted HTTPS endpoint | `LeaderAuditTrustedHttpsEndpoint.trusted` | HTTP/user-info/query/fragment/control negative test |
| POST와 request timeout | `HttpLeaderAuditDelivery.buildRequest` | method, timeout, body length assertion |
| bounded HTTP 결과 분류 | `classifyStatus`, `classifySynchronousFailure` | 2xx/408/429/5xx/4xx/I/O matrix |
| response retention 0 byte | `HttpResponse.BodyHandlers.discarding()` | body handler subscriber test |
| header/redirect boundary | `normalizeHeaders`, `followRedirects()==NEVER` guard | allow-list, CR/LF, forbidden/unknown, redirect negative tests |
| cancellation과 close race | `sendRequest` cancellation bridge + `BoundedLeaderAuditExporter` | delivery future cancel, exporter close/late 503 test |
| existing bounded exporter semantics 보존 | `HttpLeaderAuditExporter` delegate composition | admission/release/close 상태 기준 데이터 테스트 |
| 호출자 문서와 release pin parity | 루트·모듈 README, manual draft | Korean terminology audit, `git diff --check` |

## 발견 항목과 후속 조치

| 심각도 | 항목 | 현재 조치 | 후속 |
|---|---|---|---|
| P2 | retry attempt마다 encoder가 다시 실행되고 안정적인 delivery ID가 없다. | manual에 at-least-once와 receiver idempotency 책임을 명시했다. | 1.0.0 전 stable delivery ID 또는 명시적 idempotency SPI를 별도 설계한다. |
| P2 | `Content-Type`이 headers와 payload encoder 양쪽에 표현되고 encoder 값이 최종 승리한다. | 동작과 테스트에서 encoder 우선순위를 고정하고 README에 encoder 책임을 설명했다. | public API가 굳기 전 header 단일 책임 여부를 별도 결정한다. |
| P2 | 실제 JDK HTTPS/TLS loopback, timeout, body discard, cancellation 상호작용은 stub으로만 검증했다. | deterministic stub으로 callback/classification을 검증하고 범위를 명시했다. | 1.0.0 전 in-process TLS receiver smoke를 추가한다. |
| P3 | `Trusted`는 DNS/SSRF 검증 결과가 아니라 caller trust assertion이다. | KDoc/manual에 private/link-local/ULA/CGNAT·DNS rebinding 비목표와 egress proxy 경계를 명시했다. | Spring/Ktor 설정이 임의 URI를 내부에서 trust하지 않도록 후속 integration 설계를 고정한다. |

위 항목은 현재 P1 이하의 기능 결함이나 데이터 유출 증거가 아니므로 PR 생성
차단으로 승격하지 않는다. 후속 작업 없이 이번 adapter가 실제 receiver의
중복 제거를 보장한다고 주장하지 않는다.

## 성능·안정성 스캔

- 대상 소스: `leader-core/src/main/kotlin/io/bluetape4k/leader/audit/http/*.kt`
- `rg -n 'GlobalScope|runBlocking|Thread\.sleep|delay\(|synchronized|@Synchronized|runCatching' ...`: HTTP 소스에서 결과 0건
- queue/retry는 새 구현이 아니라 `BoundedLeaderAuditExporter`의 기존 bounded permit,
  caller-owned executor/scheduler와 연결된다.
- 별도 benchmark나 throughput SLA는 추가하지 않았다. 이번 성능 주장은
  non-blocking admission과 finite body/response retention에 한정한다.

## 검증 증거

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.audit.http.*' --no-build-cache --no-daemon --console=plain`: **10 tests PASS**
- `./gradlew :bluetape4k-leader-core:test --no-build-cache --no-daemon --console=plain`: **BUILD SUCCESSFUL**
- `./gradlew :bluetape4k-leader-core:detekt --no-daemon --console=plain`: **BUILD SUCCESSFUL**
- `git diff --check`: **PASS**
- `./gradlew projects --no-daemon --console=plain`: **BUILD SUCCESSFUL**, no new project/module registration
- `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs leader-core/README.ko.md leader-micrometer/README.ko.md README.ko.md docs/manual/drafts/2026-08-18-audit-export.ko.md docs/review/2026-08-26-issue-535-audit-export-review.md docs/lessons/2026-08-26-issue-535-audit-export.md`: **6 files, findings=0**
- `javap`로 payload/exporter의 public ABI와 redirect/body handler boundary를 확인했다.

실제 외부 HTTPS receiver, 인증서 체인, DNS/SSRF 정책, JSON serializer 통합은
호출자 환경과 후속 transport 범위이므로 이 검토에서 테스트하지 않았다.

## Writer gate와 PR 진입 조건

- SPW-01: 이슈·선행 head·설계/계획·local source/test를 대조했다.
- SPW-02: 구현·검증 편차, P2/P3, rollback 경계를 기록했다.
- SPW-03: reader-facing prose는 한국어로 작성하고 API/명령/status token은 보존했다.
- SPW-04: README EN/KO와 pinned manual 경계를 read-back했다.
- SPW-05: terminology audit와 `git diff --check`를 통과했다.

로컬 review gate는 `P0=0/P1=0`으로 종료한다. 다음 gate는 Lore commit,
PR 생성, exact-head hosted CI 확인이며, merge는 fresh exact-head 승인 이후에만
수행한다.
