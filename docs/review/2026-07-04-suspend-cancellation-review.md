# 취소 검토 일시 중단

날짜: 2026-07-04

범위:
- 문제: #568, #569
- 모듈: `leader-core`, `leader-exposed-r2dbc`, `leader-redis-lettuce`, `leader-redis-redisson`
- 패키지:
  - `io.bluetape4k.leader.local`
  - `io.bluetape4k.leader.exposed.r2dbc.lock`
  - `io.bluetape4k.leader.lettuce`
  - `io.bluetape4k.leader.redisson`

## 조사 결과

1. `ExposedR2dbcLock.isHeldByCurrentInstance()` 및 `unlock()`는 `suspendTransaction`를 `runCatching`로 래핑했습니다. 이는 코루틴 취소를 기록된 대체 결과로 변환했습니다.
2. `ExposedR2dbcGroupLock.isHeldByCurrentInstance()` 및 `unlock()`는 동일한 `runCatching` 일시 중단 패턴을 가졌습니다.
3. 일시 중단 전략적 선택자는 `runCatching`를 통해 `updateResult` failure를 기록했으며, 이는 success 또는 failure를 기록하는 동안 취소를 삼킬 수도 있습니다.
4. 동기식 전략 선택자는 여전히 `runCatching`를 사용하지만 정지 API를 호출하지 않으며 이 검토의 코루틴 취소 범위를 벗어납니다.

## 변경 사항

- 일시 중단 `runCatching` 블록을 명시적 `try/catch`로 대체했습니다.
- 취소되지 않은 오류를 처리하기 전에 `CancellationException`를 다시 발생시킵니다.
- 광범위한 `Throwable` 대신 백엔드/로그 폴백을 위해 `Exception`를 포착하세요.
- 회귀 테스트에서 취소 및 비취소 failure를 직접 강제할 수 있도록 패키지 내부 취소 보존 도우미를 추출했습니다.
- 이전의 취소 불가 대체 동작을 유지했습니다.
  - R2DBC `isHeldByCurrentInstance()`는 DB 오류에 대해 `false`를 반환합니다.
  - R2DBC `unlock()`는 다른 소유자의 잠금을 삭제하지 않고 백엔드 오류를 기록합니다.
  - 전략적 선택자 결과 업데이트 오류는 취소가 발생하지 않는 한 작업 결과를 숨기지 않고 기록됩니다.

## 패턴 검토

- `bluetape4k-code-patterns`: 통과
  - 일시 중단 호출과 관련된 `runCatching`는 영향을 받는 일시 중단 경로에 남아 있지 않습니다.
  - 일반 예외 처리 전에 `CancellationException`가 다시 발생합니다.
  - 생산 `runBlocking`가 추가되지 않았습니다.
  - `!!`가 추가되지 않았습니다.
  - 공개 API 형태는 변경되지 않았습니다.

## 검증

- `./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-core:compileTestKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileTestKotlin :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-redis-redisson:compileTestKotlin --warning-mode all`
  - 통과, `BUILD SUCCESSFUL in 13s`.
- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.local.LocalStrategicSuspendLeaderElectorTest' --warning-mode all`
  - PASS, 14개 테스트.
- `./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests 'io.bluetape4k.leader.exposed.r2dbc.lock.R2dbcLockCancellationTest' --tests 'io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcLockTest' --tests 'io.bluetape4k.leader.exposed.r2dbc.lock.ExposedR2dbcGroupLockTest' --warning-mode all --rerun-tasks`
  - PASS, `R2dbcLockCancellationTest`를 포함하여 H2, PostgreSQL 및 MySQL에 대한 74개 테스트.
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicSuspendLeaderElectorTest' --warning-mode all`
  - PASS, 17개 테스트.
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonStrategicSuspendLeaderElectorTest' --warning-mode all`
  - PASS, 11개 테스트.
- 정적 스캔:
  - `runCatching`는 이 검색 패턴에 대한 동기식 전략적 선택기 구현에만 남아 있습니다.
