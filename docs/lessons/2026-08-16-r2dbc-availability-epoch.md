# 배운 교훈 - R2DBC DB-time availability의 인과 순서

## 맥락

Issue #691의 Exposed R2DBC 그룹 선출기는 DB time 조회가 실패하면 해당
`lockName`의 `activeCount`, `availableSlots`, `state`를 fail-closed로 유지해야
합니다. 그러나 실패 뒤에 완료된 조회라도 실제 시작 시점이 더 이르면, 최신 장애를
복구 증거로 사용할 수 없습니다.

## 원인

기존 구현은 `unavailableLockNames`를 Boolean marker처럼 추가하거나
제거했습니다. 먼저 시작한 `activeCountSuspend`가 대기하는 동안 다른 선출 시도가
DB time 조회에 실패해 marker를 추가해도, 오래된 조회가 나중에 성공하면 marker를
무조건 제거했습니다. 성공과 실패의 발생 순서를 저장하지 않아 완료 순서가 상태를
결정한 것이 원인입니다.

## 결정

전역 `availabilityEpoch`와 `lockName`별 최신 실패 epoch를 함께 저장합니다. DB
작업은 시작 전에 epoch 스냅숏을 남기고, 성공 시점에는 같은 키의 `compute`에서 최신
실패 epoch와 비교합니다. 작업 시작 뒤에 기록된 실패가 있으면 marker를 유지하고
`maxLeaders`를 반환합니다. 장애보다 늦게 시작한 DB 작업이 성공한 경우에만 marker를
제거합니다.

`markUnavailable`과 `markAvailable`은 모두 같은 키의 `compute`를 사용합니다.
이렇게 해야 epoch 증가와 marker 저장 사이에 성공 경로가 끼어드는 경우도 같은
인과 규칙으로 직렬화됩니다.

## 결과

오래된 성공은 최신 DB-time 장애를 숨기지 못합니다. 장애 구간에는
`activeCount=maxLeaders`, `availableSlots=0`을 유지하고, 이후 새로 시작한 DB
조회가 성공하면 정상 캐시 값과 슬롯 수를 다시 노출합니다. 공개 API와
skip-on-contention 계약은 변경하지 않았습니다.

## 검증

- RED: H2, PostgreSQL, MySQL에서 오래된 조회 성공이 marker를 제거해 3개 테스트 실패
- GREEN: 같은 세 dialect의 순서 고정 회귀 테스트 3개 통과
- 그룹 선출기 테스트 57개 통과
- `bluetape4k-leader-exposed-r2dbc` 모듈 테스트 312개와 Detekt 통과
- `git diff --check` 통과

## 향후 지침

비동기 상태 갱신에서 Boolean availability marker를 무조건 지우지 마세요. 성공이
장애를 해제하려면 작업 시작 시점이 최신 실패보다 뒤라는 인과 증거가 필요합니다.
테스트는 단순 동시 실행 횟수보다 SQL 경계의 선후 관계를 barrier로 고정하고,
fail-closed 유지와 후속 정상 복구를 함께 검증해야 합니다.
