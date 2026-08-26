---
title: "Management action 운영과 quarantine runbook"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Management action 운영과 quarantine runbook

> Issue #532를 위한 unreleased 초안입니다. Management-action 계약이 `sourceReleaseCommit`보다 새 API이므로 pinned `0.5.0` manual에서 제외했습니다. 다음 release manifest가 갱신된 뒤 API 초안과 함께 승격하세요.

## 운영 경계

Management action은 bounded process-local lease cleanup control입니다. 특정 인스턴스가
lease를 내려놓아야 한다는 운영 근거가 있을 때 사용하지만, 다른 인스턴스가 소유권을
가져야 한다는 증거를 만들지는 않습니다. 구체적인 single-leader lease handle만
등록하세요. Group/semaphore·strategic election, route-runtime lease, scheduled job,
force unlock, rename, conversion에는 사용하지 않습니다.

인증, 권한, registry scope, HTTP adapter 노출 결정은 애플리케이션이 소유합니다.
Shutdown drain이 시작되면 새 admission은 닫히며 registration token은 lease release
명령이 아니라 reference입니다.

## Outcome 및 HTTP matrix

Spring과 Ktor adapter는 동일한 framework-neutral mapping을 사용합니다.

| Core outcome | HTTP | `mutationAttempted` | Retry |
|---|---:|:---:|---|
| `RELEASED` | 200 | true | 자동 금지 |
| `INVALID_LOCK_NAME` | 400 | false | selector 수정 |
| `NOT_REGISTERED` | 404 | false | 무조건 반복 금지 |
| `AMBIGUOUS` | 409 | false | 중복 registration 제거 |
| `NOT_HELD` | 409 | false | 소유권 재조회 |
| `ACTION_IN_PROGRESS` | 409 | false | 기존 action 완료 대기 |
| `ACTION_ADMISSION_REJECTED` | 429 | false | backoff 후 capacity 확인 |
| `OWNERSHIP_UNKNOWN` | 503 | false | backend/lease 상태 확인 |
| `RELEASE_UNCONFIRMED` | 503 | true | 중지; 성공으로 승격 금지 |
| `RELEASE_FAILED` | 503 | true | 중지; backend와 quarantine 조사 |
| `REGISTRY_CLOSED` | 503 | false | lifecycle 재구성 |
| `ACTION_TIMED_OUT` | 504 | false 또는 true | terminalization 대기; 자동 금지 |

응답 body는 action, outcome, mutation flag만 포함합니다. `retryAllowed`는 항상
`false`입니다. Release 시작 후 timeout은 backend를 변경했을 가능성이 있으므로 이
flag를 근거로 다시 release하지 마세요.

## Quarantine 신호

Core registry는 cleanup이 실제로 끝날 때까지 admission capacity를 보존합니다.
Cleanup timeout, non-interruptible callback, callback error, close timeout이면
reservation을 quarantine할 수 있습니다. Observation과 Micrometer metric은 고정된
low-cardinality phase/reason/surface 값만 사용하며 lock name, actor, credential,
request ID, token, backend payload, exception message를 담지 않습니다.

다음 신호를 함께 관찰하세요.

- phase와 surface별 action outcome count
- 고정 reason별 quarantine counter
- worker가 종료된 뒤에만 0으로 돌아오는 active/quarantined gauge
- backend diagnostics와 application shutdown/drain 결과

Stale status 기준 데이터나 성공한 connectivity probe만으로 소유권을 판단하지 마세요.
Backend의 conditional ownership semantics로 상태를 재확인해야 합니다.

## Disable 및 rollback runbook

1. Nested action property를 끄거나 `leaderElectionActions`/Ktor route를 HTTP exposure에서
   제거합니다. Parent read-only status endpoint는 유지합니다.
2. Unauthenticated canary와 허용된 internal canary로 write endpoint가 없는지 확인합니다.
   401/403이면 route가 여전히 설치된 것이고, 부재는 404입니다.
3. 추가 action 전에 인증과 network policy를 확인합니다. Credential이나 request detail을
   ticket, log, metric tag, curl history에 남기지 마세요.
4. 배포 window 동안 typed outcome, quarantine counter, active gauge recovery, backend
   diagnostics를 관찰합니다.
5. `RELEASE_UNCONFIRMED`, `RELEASE_FAILED`, 또는 `mutationAttempted=true` timeout이면
   retry를 중지하고 worker/quarantine과 backend 상태가 수렴할 때까지 소유권을 unknown으로
   취급합니다.
6. 애플리케이션 lifecycle coordinator로 registry 또는 instance를 drain하고 교체합니다.
   Drain 실패가 무관한 application scope를 취소하게 두지 마세요.
7. Bounded canary, 문서화된 authorization rule, rollback switch가 준비된 뒤에만 action을
   다시 활성화합니다.

## Shutdown 순서

Engine/context를 멈추기 전에 adapter graceful shutdown helper를 호출하세요. Helper는
admission을 닫고 bounded 시간 안에 기존 worker를 기다린 뒤, drain 결과가 false이면
sanitized warning을 남기고 engine stop을 계속합니다. 임의의 lease를 해제하거나 global
shutdown listener를 설치하거나 호출자의 application scope를 취소하지 않습니다.

## 승격 조건

Spring·Ktor 초안, exact-release test evidence, sanitized metric/log review, release
manifest provenance가 일치할 때에만 이 runbook을 versioned manual로 승격합니다. 그
전까지 모든 management-action 지침은 unreleased 운영 가이드로 취급하세요.
