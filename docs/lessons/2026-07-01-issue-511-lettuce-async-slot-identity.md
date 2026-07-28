# 배운 교훈 - 문제 511 Lettuce 비동기 슬롯 ID(2026-07-01)

## 맥락

Issue #511에서는 Lettuce 비동기 `LeaderSlot` API가 기본 브리지 경로를 상속한 것으로 나타났습니다. 블로킹 및 suspend API는 `slot.leaderId`를 유지하지만 비동기 단일/그룹 결과 API는 `LeaderRunResult.Elected(..., leaderId = null)`를 반환하고 브리지 경고를 내보낼 수 있습니다.

## 결정

`leader-core` 테스트 고정 장치에 비동기 슬롯 ID 계약 고정 장치를 추가하고 Lettuce 단일/그룹 비동기 선출기가 두 슬롯 변형을 재정의하도록 합니다.

- `runAsyncIfLeader(slot, ...)`
- `runAsyncIfLeaderResult(slot, ...)`

그룹 비동기 획득은 `slot.leaderId`를 `tryAcquireAsync`로 전달해야 Redis 슬롯 메타데이터가 동기화 및 일시 중지 동작과 일치합니다.

## 결과

실패한 비동기 계약 테스트는 먼저 문제를 재현한 다음 Lettuce 구현 변경 후 통과했습니다.

검증 증거:

- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests '*LettuceAsyncLeader*LeaderIdContractTest' --no-parallel`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --no-parallel`
- 테스트 XML 요약: 테스트 221개, 실패 0개, 오류 0개, 건너뛰기 0개.

## 퓨쳐 가드

백엔드가 슬롯 인식 차단 또는 정지 API를 구현하는 경우 비동기 슬롯 API를 별도로 검증하세요. 기본 브리지 방법은 의도적으로 `leaderId`를 경고하고 삭제합니다. 백엔드 모듈은 감사 ID를 유지하기 위해 두 비동기 슬롯 변형을 모두 재정의해야 합니다.
