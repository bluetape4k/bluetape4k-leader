# Lessons Learned — FAIL_OPEN_RUN failureMode (2026-05-06)

**관련 PR**: #116
**영향 모듈**: leader-core, leader-spring-boot

## L1: SkipReason의 의미를 '원인'이 아닌 '결과 상태'로 설계해야 함

### 문제
초기 구현에서 backend 예외 + FAIL_OPEN_RUN 경우에 `BACKEND_ERROR`를 발행했다.
코드 리뷰에서 이것이 `SkipReason.FAIL_OPEN_FORCED` KDoc의 "경쟁 또는 백엔드 예외 후 FAIL_OPEN_RUN으로 실행" 설명과 불일치를 지적받았다.

### 교훈
`SkipReason`은 락이 없는 **원인**(CONTENTION / BACKEND_ERROR)을 기록하는 것보다,
실제로 락 없이 실행된 **사실** (`FAIL_OPEN_FORCED`)을 기록하는 것이 관측 가능성 관점에서 더 유용하다.
대시보드 alert "락 없이 본문 실행 횟수"를 `FAIL_OPEN_FORCED` 하나로 집계할 수 있고,
원인 구분은 INFO 로그 레벨에서 이미 `reason=CONTENTION` / `reason=BACKEND_ERROR`로 제공된다.

**구현 패턴**: backend catch의 `onLockNotAcquired`를 `when (failureMode)` 분기 안으로 이동하여
RETHROW/SKIP은 `BACKEND_ERROR`, FAIL_OPEN_RUN은 `FAIL_OPEN_FORCED`를 발행.

---

## L2: Lettuce connect()는 즉시 TCP 연결 — 백엔드 오류 시뮬레이션에 ToxiProxy 필요

### 문제
"연결을 닫아서 백엔드 오류를 시뮬레이션" 접근은 Lettuce의 공유 `StatefulRedisConnection`을 닫아버려 다른 테스트에 영향을 준다. Redisson.create()는 bad URL을 즉시 throw한다.

### 교훈
실제 백엔드 네트워크 장애를 시뮬레이션하려면 ToxiProxy를 사용해야 한다.
패턴: `Network.newNetwork() → RedisServer(network) → ToxiproxyServer(network) → proxy.delete()`.
공유 연결을 닫지 않아 다른 테스트에 영향 없음.

---

## L3: Freefair CTW는 main sourceSet만 위빙 — 통합 테스트는 직접 호출

### 문제
CTW는 컴파일 시점에 main 클래스만 위빙하므로 test 클래스의 `@LeaderElection` 어노테이션은 인터셉션되지 않는다.

### 교훈
통합 테스트에서는 `aspect.aroundLeader(pjp)` 직접 호출 패턴을 사용한다.
`ProceedingJoinPoint`와 `MethodSignature`는 MockK로 모킹한다.
`configureJoinPoint(method, target)` 헬퍼로 반복 제거.
