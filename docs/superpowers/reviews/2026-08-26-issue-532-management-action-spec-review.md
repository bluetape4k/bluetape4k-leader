# Issue #532 management action 명세 통합 리뷰

## DoD Status

- 상태: `READY_FOR_USER_SPEC_APPROVAL` — 구현 전 명세 검토 단계
- 범위: Core 소유 handle registry, blocking/suspend action, Spring Actuator web
  endpoint, Ktor authenticated route, HTTP/retry 계약, quarantine 관측성, EN/KO
  manual 운영 절차
- 관련 이슈: [#532](https://github.com/bluetape4k/bluetape4k-leader/issues/532)
- 상위 Epic: [#699](https://github.com/bluetape4k/bluetape4k-leader/issues/699)
- 기준 브랜치: `origin/develop` `783f38ba306a44d35118414b9838d55618caae2a`
- 설계 선택: **Core가 소유 핸들 registry를 제공하고 framework adapter가 opt-in
  write surface를 연결한다.** (사용자 승인)
- 구현 상태: 시작하지 않음. 명세 승인 전에는 구현 계획과 production code를
  변경하지 않는다.

## 독립 관점 리뷰

| 관점 | 초기 판정 | 초기 findings | 명세 반영 및 통합 판정 |
| --- | --- | --- | --- |
| Developer/API | `REJECT` | P0 0, P1 5, P2 1 | typed registration, registry-owned scheduler, 공통 lock helper, lifecycle helper, DTO/HTTP 계약 반영 → P0/P1 0 |
| User/Caller | `REJECT` | P0 0, P1 6, P2 2 | HTTP/retry matrix, 작업 비취소 의미, manual parity, JMX 보존, Ktor path/selector, migration/runbook 반영 → P0/P1 0 |
| Operations | `REJECT` | P0 0, P1 3, P2 3 | bounded drain 결과, quarantine metric/gauge/log, sanitized observer와 #535 경계, rollout/rollback 절차 반영 → P0/P1 0 |
| Security | `PASS` | P0 0, P1 0, P2 0, P3 0 | `@WebEndpoint` web-only, Ktor `authenticate` + required authorize, raw secret/selector 비노출 유지 |
| Performance | `PASS` | P0 0, P1 0, P2 0, P3 0 | registry-owned bounded scheduler/scope, O(1) registration, 즉시 admission rejection, timeout/quarantine cap 유지 |
| Stability | `REJECT` 후 보강 | P0 0, P1 0, 잔여 P2는 capacity/quarantine/Error cleanup | blocking/suspend capacity 분리, phase/CAS, `Error` finally cleanup, non-interruptible quarantine, bounded close 계약 반영 → P0/P1 0 |

초기 `REJECT` 항목은 구현 전에 모두 명세 문장·API·acceptance로 반영했다. 안정성
관점의 마지막 독립 lane은 중단되었으므로, 아래 통합 검토에서 해당 P2를 다시
대조했고 구현 단계의 회귀 테스트를 필수 acceptance로 남겼다. 구현 전 단계의
차단 등급은 현재 P0 0, P1 0이다.

## 핵심 결정과 검증 가능성

1. Core registry는 명시적으로 등록된 `LeaderLeaseHandle` 또는
   `SuspendLeaderLeaseHandle`만 다룬다. identity 기반 registration, exactly-one
   예약, `HELD` pre-check, idempotent conditional release, post-check를 통해
   이름만으로 다른 JVM lease를 조작하지 않는다.
2. registration은 `LeaderManagementRegistration(accepted, outcome)` token으로
   성공·invalid name·capacity·closed를 즉시 구분한다. token close는 O(1)이며
   lease를 해제하지 않는다. management action은 lease만 변경하고 application
   작업, coroutine/job, 외부 side effect를 취소하지 않는다.
3. blocking path는 기존 `LeaseOperationScheduler`의 bounded
   `ThreadPoolExecutor`/`AbortPolicy`를 registry가 소유해 재사용한다. suspend path는
   registry-owned `SupervisorJob`과 in-flight cap을 사용하고 blocking executor나
   `runBlocking`을 사용하지 않는다.
4. 결과는 `mutationAttempted` phase 불변식을 가지며, Spring/Ktor는 같은 JSON
   allow-list와 HTTP/retry matrix를 사용한다. Spring은 기존
   `@Endpoint(id="leaderElection")` status/JMX를 유지하고
   `@WebEndpoint(id="leaderElectionActions")` write endpoint를 별도로 추가한다.
5. Ktor POST는 자동 설치하지 않고 `authenticate("management")` 내부의 명시적
   `Route` extension으로만 설치한다. action path는 기존 status path에 `/actions`를
   붙이는 단일 source를 기본으로 하며, matcher 단계 404와 handler 단계
   `400 INVALID_LOCK_NAME`을 구분한다. `closeAndDrain()`은 engine stop 전에
   application-owned suspend coordinator가 호출한다.
6. quarantine은 저카디널리티
   `bluetape4k.leader.management.quarantine` counter,
   `.quarantine.active` gauge, `leader-management-quarantine` log code로 관찰한다.
   actor/lock/token/credential/exception 원문은 저장하지 않으며 durable audit
   export는 #535 후속 범위다.

## 증적

| 검증 | 결과 |
| --- | --- |
| 기준 브랜치 확인 | `origin/develop`와 기준 SHA 일치 |
| 기존 관련 모듈 baseline | `./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-spring-boot:test :bluetape4k-leader-ktor:test --no-configuration-cache --max-workers=1` → `BUILD SUCCESSFUL` |
| 명세 whitespace | `git diff --check` → 통과 |
| 한국어 기술 용어 | `audit-korean-terms.mjs` → `findings=0` |
| 외부 계약 확인 | Spring custom/write/web endpoint 및 JMX 공식 문서, Ktor auth 공식 문서 링크를 명세 §2.2에 보존 |
| workflow receipt | `bluetape-flow.py verify --run-id 20260826T013743Z-2ba8e0a3` → `ok=true`, sequence 9 |

## 구현 전 잔여 WATCH

- 기존 `LeaseOperationScheduler`의 고정 shutdown timeout과 registry `closeTimeout`을
  구현에서 일치시키고, non-interruptible callback의 실제 Future 종료 전에는
  capacity를 재사용하지 않는지 검증한다.
- Ktor 버전의 encoded delimiter matcher가 실제로 404를 만드는지 integration test로
  확인하고, handler에 전달되는 hostile selector는 400으로 고정한다.
- `LeaderManagementActionObserver` 예외 격리, quarantine active gauge 회복,
  Spring context/Ktor engine 종료 순서를 targeted test로 고정한다.
- runtime 자동 registration, group action, force refresh/stale cache/backend-wide
  mutation, durable actor audit는 #532에 추가하지 않고 후속 이슈로 유지한다.

## 최종 판정

`READY_FOR_USER_SPEC_APPROVAL` — 선택안 1에 대한 명세와 독립 관점 통합 검토가
완료되었고 구현 전 P0/P1 blocker는 없다. 사용자가 이 명세를 승인하면 다음
단계에서 별도 구현 계획을 작성·검토한 뒤 TDD 구현으로 진행한다. 이 판정은 PR
생성·merge 승인이 아니며, 구현 후에는 새 코드 기준으로 독립 리뷰와 exact-head
CI/merge 게이트를 다시 수행한다.
