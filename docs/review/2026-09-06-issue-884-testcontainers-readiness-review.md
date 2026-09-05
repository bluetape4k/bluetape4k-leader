# Issue #884 Testcontainers readiness inline 코드 리뷰

## 범위와 판정

- 기준: `origin/develop@65731c0b4a0f046bae4c85a97ee4646c95d27ee1`
- branch: `fix/issue-884-testcontainers-readiness`
- 범위: `leader-core` test fixtures, Lettuce/Redisson Toxiproxy tests, etcd tests, 설계·계획·lesson
- 방식: 독립 lane이 결과를 반환하지 못해 사용자 지침에 따라 `gpt-5.6-luna max` inline 검토
- 최종 판정: `P0 0 / P1 0 / 미해결 P2 0`

## 7관점 검토

### 1. Kotlin와 correctness

- `ReadinessBoundaryDiagnostic`은 `PORT_MAPPING`, `CONTAINER_SERVICE`, `HOST_FORWARDING`, `UNKNOWN`의 우선순위를 명시적으로 고정한다.
- delegate 성공 시 collector를 호출하지 않고, 실패 시 원 throwable을 cause로 유지한다.
- 새 production `!!`, suspend `runCatching`, cancellation 변환, blocking production path는 없다.
- 동일 타입 원시 인자 묶음은 `ReadinessEndpoint`로 제한하고 path/port/name을 생성 시 검증한다.

판정: PASS.

### 2. lifecycle와 cleanup

- internal helper는 target과 network namespace만 공유하고 `finally`에서 즉시 중지한다.
- etcd shared fixture는 기존 launcher와 같은 `reuse=false`, 단일 start, `ShutdownQueue.register(this)` ownership을 유지한다.
- Toxiproxy의 Redis/network/toxic/client cleanup 순서는 변경하지 않는다.
- collector 실패도 원 wait failure를 덮지 않는 unit test를 추가했다.

판정: PASS.

### 3. timeout와 성능

- 정상 wait 성공 경로에서는 추가 Docker/HTTP probe가 0회다.
- failure 경로의 host connect/read는 각각 2초, helper startup/probe는 one-shot 5초다.
- 기존 startup timeout을 늘리지 않았고 정상 Colima를 재시작하지 않았다.
- 잔여 위험: Alpine digest가 로컬에 없을 때 image pull/registry latency는 helper startup check보다 먼저 발생할 수 있다. pull/helper 실패는 `UNKNOWN`으로 저하되고 원 cause는 유지된다.

판정: PASS, 비차단 운영 위험 기록.

### 4. 보안과 진단 노출

- Docker inspect는 requested port binding과 status/running/exitCode만 출력한다.
- environment, label, mount, credential-bearing command, 전체 inspect payload는 수집하지 않는다.
- response/error detail은 한 줄 256자로 제한한다. 적용 endpoint인 `/version`, `/health`는 credential을 포함하지 않는다.

판정: PASS.

### 5. API/ABI와 공개 변경

- production source와 `**/api/*.api`에는 diff가 없다.
- `leader-core`의 `testFixturesApiElements`는 공개 `*-test-fixtures.jar` variant이므로 Testcontainers API dependency와 `ReadinessEndpoint`/factory는 additive 공개 변경이다.
- 초기 구현에서 공개됐던 classifier/collector/wrapper는 inline 리뷰에서 `internal`로 축소했다. main runtime dependency와 production ABI에는 영향이 없다.

판정: PASS, test-fixtures additive change를 PR에서 명시해야 함.

### 6. 테스트와 재현성

- 유효한 RED는 wished-for fixture symbol 미해결로 실패했고 dependency/테스트 오타 실패가 아니었다.
- unit 9개가 분류, 정상 no-op, cause 보존, collector failure, detail bound, timeout 전달을 고정한다.
- 실제 etcd proof는 service readiness 성공 직후 합성 delegate failure를 발생시켜 제거 전에 internal/host/mapping이 모두 `SUCCESS`인지 확인한다.
- clean matrix는 Lettuce 2개, Redisson 3개, etcd 157개를 5회 순차 실행했고 모든 single-use daemon이 exit 0이었다.
- affected 4개 모듈 1,914개와 전체 build 4,370개가 failures/errors/skips 0으로 통과했다.

판정: PASS.

### 7. CI·문서·유지보수

- module 추가/rename, workflow, nightly 변경이 없어 module wiring checklist는 N/A다.
- 새 KDoc, 설계, 계획, lesson은 한국어이고 public README/API 사용법 변경은 없다.
- `detekt`, `git diff --check`, production API diff 검증이 통과했다.
- exact-head PR CI는 PR 생성 전이므로 `PENDING`이다. 1인 개발자 workflow에서 human-review subgate만 `N/A`이며 기술 검증과 CI gate는 유지한다.

판정: LOCAL PASS / PR CI PENDING.

## 리뷰 중 발견 및 조치

| 심각도 | 발견 | 조치 | 상태 |
|---|---|---|---|
| P2 | collector 자체 실패 시 원 wait cause 보존이 직접 테스트되지 않음 | 원 cause identity와 `UNKNOWN` diagnostic을 검증하는 unit test 추가 | 해결 |
| P2 | detail 한 줄·256자 제한이 직접 테스트되지 않음 | newline normalization과 256자 상한 test 추가 | 해결 |
| P2 | classifier/collector/wrapper까지 test-fixtures 공개 API로 노출 | backend가 쓰는 endpoint/factory만 공개하고 나머지를 `internal`로 축소 | 해결 |

## 최종 검증 증거

- `ReadinessBoundaryWaitStrategyTest`: 9/9
- `EtcdReadinessBoundaryIntegrationTest`: 1/1
- 5회 clean matrix: Lettuce 10/10, Redisson 15/15, etcd 785/785
- affected full tests: 1,914/1,914
- full build: 4,370/4,370, 9분 14초
- `detekt`: 38/38 tasks
- failures/errors/skips: 0
- production API dump diff: 0
- final `git diff --check`: PASS

## 남은 게이트

- PR 생성: `PENDING` — 대상 `bluetape4k/bluetape4k-leader`, base `develop`, head `fix/issue-884-testcontainers-readiness`
- exact-head PR CI: `PENDING`
- merge: `PENDING` — CI, thread/read-back, mergeability, fresh exact-head 승인 필요
- human review: `N/A` — solo maintainer subgate에 한함
