# 배운 교훈 - 문제 512 Redisson 비동기 슬롯 ID(2026-07-01)

## 맥락

Issue #512에서는 Redisson 비동기 `LeaderSlot` API가 기본 브리지 경로를 상속한 것으로 나타났습니다. 블로킹 및 suspend API는 `slot.leaderId`를 유지하지만 비동기 단일/그룹 결과 API는 `LeaderRunResult.Elected(..., leaderId = null)`를 반환하고 브리지 경고를 내보낼 수 있습니다.

## 결정

두 슬롯 변형을 재정의하여 Redisson 단일/그룹 선출기에서 Lettuce 비동기 슬롯 ID 수정 사항을 미러링합니다.

- `runAsyncIfLeader(slot, ...)`
- `runAsyncIfLeaderResult(slot, ...)`

단일 잠금 비동기 실행은 비동기 작업을 생성하는 동안 실제 `LeaderLockHandle`를 푸시합니다. 또한 그룹 비동기 실행은 획득한 허가에 대해 `slot.leaderId`를 Redisson 감사 맵에 기록하고 비동기 정리 중에 해당 항목을 제거합니다.

## 결과

실패한 비동기 계약 테스트는 먼저 문제를 재현한 다음 Redisson 구현 변경 후 통과했습니다.

검증 증거:

- `./gradlew :bluetape4k-leader-redis-redisson:test --tests '*RedissonAsyncLeader*LeaderIdContractTest' --no-parallel`
- `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`
- 테스트 XML 요약: 198개 테스트, 0개 실패, 0개 오류, 0개 건너뛰기.

## 퓨쳐 가드

백엔드가 슬롯 인식 차단 또는 정지 API를 구현하는 경우 비동기 슬롯 API를 별도로 검증하세요. 기본 브리지 방법은 의도적으로 `leaderId`를 경고하고 삭제합니다. 백엔드 모듈은 감사 ID를 유지하기 위해 두 비동기 슬롯 변형을 모두 재정의해야 합니다.

활성 문제가 해당 동작을 명시적으로 다루지 않는 한 Redisson 비동기 슬롯 ID 수정을 릴리스 완료 순서 변경으로 확장하지 마세요.
