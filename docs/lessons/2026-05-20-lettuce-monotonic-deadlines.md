# Lettuce 단조로운 획득 마감일

## 맥락

Issue #309에서는 Lettuce 잠금 및 슬롯 획득 경로에서 벽시계 `System.currentTimeMillis()` 대기 예산을 발견했습니다. 백엔드는 리스 만료를 위해 이미 Redis 서버 시간을 사용하므로 안전하지 않은 시계는 클라이언트 측 재시도 기한으로 제한되었습니다.

## 결정

`System.nanoTime`를 기반으로 Lettuce-로컬 `MonotonicDeadline` 도우미를 추가합니다. 잠금 획득 및 슬롯 토큰 획득을 블로킹, 비동기 및 일시 중지하는 데 사용합니다. 고정 재시도 지연을 남은 단조 예산으로 고정하여 마지막 절전 모드가 전체 50ms 회전 간격만큼 요청된 대기 시간을 의도적으로 초과하지 않도록 합니다.

## 결과

클라이언트 측 획득 대기는 더 이상 벽시계 조정에 의존하지 않으며 리스 만료에 대한 Redis 서버 시간 Lua 의미는 변경되지 않습니다.

## 검증

- `./gradlew :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin --no-build-cache --stacktrace`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.internal.MonotonicDeadlineTest' --no-build-cache --stacktrace`
  - 5 통과
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceLeaderGroupElectionTest.maxLeaders 동시 점유 + 모두 minLease 보유 - 추가 client 는 실패한다' --no-build-cache --stacktrace`
  - 1 통과
- `./gradlew :bluetape4k-leader-redis-lettuce:test --no-build-cache --stacktrace`
  - 첫 번째 검토 후 실행에는 시간에 민감한 그룹 minLease 오류가 하나 있었습니다.
  - 즉시 대상 재실행 및 전체 모듈 재실행이 통과되었습니다.
  - 최종 전체 모듈 결과: 212 통과

## 향후 지침

Redis 백엔드의 경우 Redis 서버 시간에 리스/만료 결정을 유지합니다. 로컬 재시도 예산, 경과 기간 및 대기 기한에 대해서만 단순 JVM 시간을 사용하세요.
