# 6계층 코드 검토 - Issue #345 Consul 런타임 슬라이스

범위: `leader-consul` 단일 리더 블로킹 및 `CompletableFuture` 런타임, 내부 Java 21 HTTP 경계, 소유자 페이로드/상태 매핑, 테스트 및 README 상태 업데이트.

## 평결

- 지역 코덱스 6계층: 후속 P2로 승인합니다.
- Claude 최종 조언자: COMMENT로 승인하세요.
- 게이트: P0=0, P1=0.
- 클로드 유물:
  - `.omx/artifacts/ask-claude-code-review-consul-runtime-20260522232205.md`
  - `.omx/artifacts/ask-claude-code-review-consul-runtime-final-20260522232633.md`

## 계층 1 - 보안

- PASS: Consul ACL 토큰은 `X-Consul-Token`를 통해서만 전달됩니다. README 및 엔드포인트 `toString()`는 토큰 공개를 방지합니다.
- 통과: `keyPrefix`는 이제 `[a-zA-Z0-9_\-./:]`만 허용하여 쿼리/해시/컨트롤과 같은 경로 삽입을 거부합니다.
- PASS: 잠금 이름은 핵심 규칙에 의해 검증되고 Consul KV를 사용하기 전에 최종 경로 세그먼트로 인코딩됩니다.

## 계층 2 - 운영/SRE 안정성

- PASS: 일반 경합이 `null`를 반환합니다.
- PASS: 작업 실패 시 세션이 해제/파기되고 재획득이 허용됩니다.
- 통과: `waitTime`가 Consul 갱신 지연을 초과하면 대기 후보자가 자신의 세션을 갱신합니다. 이는 인수 중에 `invalid session`를 방지합니다.
- PASS: 중단된 `minLeaseTime` 절전 모드는 인터럽트 플래그를 복원하지만 여전히 Consul `release`/`destroy`를 실행합니다.
- WATCH: `.get(10, TimeUnit.SECONDS)` 호출 차단은 `ConsulEndpoint.requestTimeout`에서 파생되지 않습니다. 안정적인 프로모션 전 후속 조치로 추적하세요.

## 계층 3 - 구조적 영향

- 통과: 공개 API는 bluetape4k 소유 `ConsulEndpoint`, `ConsulLeaderElectionOptions`, `ConsulLeaderElector` 및 확장 도우미만 노출합니다.
- 통과: Consul HTTP 세부 정보는 내부 `ConsulLockClient` 뒤에 남아 있습니다.
- 시청: 이 슬라이스에서는 사용자 정의 `HttpClient`/TLS/프록시 주입이 공개되지 않습니다.

## 계층 4 - Kotlin/코드 품질

- 통과: 정리 경로는 중단 의미 체계를 보존하고 정리 절전 실패를 통해 작업 예외를 삼키는 것을 방지합니다.
- 통과: `ConsulLockExtendDelegate.isHeld()`는 이제 세션 갱신 부작용이 아닌 수동적 읽기 전용 소유권 검증입니다.
- 통과: 공개 API에는 영어 KDoc가 있습니다.

## 계층 5 - 테스트 / 유형 / 자동 실패

- 통과: `:bluetape4k-leader-consul:test`는 25개의 테스트를 실행합니다.
- 통과: 테스트에는 옵션/키 검증, 소유자 페이로드 왕복, 백엔드 오류 분류, 상태 매핑, 경합 건너뛰기, 경합 시 삭제 실패, 중단된 정리, 대기 중인 후보 갱신, 비동기 행복한 경로, `LeaderRunResult.ActionFailed`, 작업 실패 정리 및 실제 Consul TTL 인계가 포함됩니다.
- 주의: 릴리스/파기 실패는 설계상 최선의 경고로만 유지됩니다.

## 계층 6 - 성능/안정성

- 통과: 폴링 루프에는 고정된 50ms 절전 및 엄격한 `waitTime` 기한이 있습니다.
- 통과: 실제 Consul 만료 인수 테스트는 Consul TTL 만료 차이를 설명하기 위해 더 넓은 시간 제한 예산을 사용합니다.
- 주의: 비동기 릴리스는 `minLeaseTime`에 대해 호출자가 제공한 실행기를 차단할 수 있습니다. 현재 README/기본값은 가상 스레드를 선호하지만 향후 정리 실행기 또는 문서 메모를 통해 놀라움을 줄일 수 있습니다.

## 검증

- `git diff --check`
- `./gradlew :bluetape4k-leader-consul:test --no-daemon --console=plain`
  - 통과: 25개 테스트.
- `./gradlew :bluetape4k-leader-consul:check --no-daemon --console=plain`
  - 통과.

## 후속 후보자

- 엔드포인트/요청 시간 초과 정책에서 차단 `.get(...)` 제한을 파생시킵니다.
- Consul 선출기가 사용자 정의 `HttpClient` 구성을 노출해야 하는지 여부를 결정합니다.
- `minLeaseTime`가 0이 아닐 때 비동기 정리에서 차단되는 실행기를 문서화하거나 격리합니다.
