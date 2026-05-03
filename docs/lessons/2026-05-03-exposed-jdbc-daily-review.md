# 2026-05-03 leader-exposed-jdbc Daily Review (5-Tier)

## 배경

`leader-exposed-jdbc` 모듈이 직전 24시간 내에 신규 도입됨. 5개 코드 리뷰 에이전트(코드 일반/silent failure/타입 설계/KDoc/테스트 커버리지)를 병렬 실행하여 수정 사항 도출.

## 적용된 수정

### CRITICAL

1. **`Thread.sleep` `InterruptedException` 누수** — `ExposedJdbcLock.tryLock` / `ExposedJdbcGroupLock.tryLock`의 retry 루프에서 `Thread.sleep`이 `runCatching` 외부에 위치 → `runIfLeader`의 "never-throws" 계약 위반 가능. `try/catch (InterruptedException)`으로 보호하고 interrupt flag 복원 후 false 반환.
2. **`runCatching`의 `CancellationException` 흡수** — `tryAcquireOnce`를 `runCatching`으로 감싸 `CancellationException`까지 흡수. `try/catch(CancellationException) { throw }` 명시적 재전파로 변경.
3. **`RetryStrategy` 변형의 검증 부재** — `Jitter(baseDelayMs=0)`, `Exponential(maxDelayMs<baseDelayMs)`, `Fixed(0)` 모두 무검증으로 생성 가능. 각 변형에 `init { require(...) }` 추가, `delayMs(remaining<=0)` 동작을 명시 (0 반환).

### HIGH

4. **Async `whenCompleteAsync` `CancellationException` 미처리** — `CompletableFuture` cancel 시 `j.u.c.CancellationException`이 throwable로 전달되어 FAILED 이력 기록됨. 동기 path와 일관되게 cancel은 history skip 처리.
5. **`ExposedJdbcGroupLock.slot` 검증 부재** — 음수 slot 허용. `init { require(slot >= 0) }` 추가.
6. **Magic 255** — `lockOwner` 길이 검증의 magic literal을 `ExposedLeaderConstants.LOCK_OWNER_LENGTH`로 통일 (스키마 컬럼 폭과 단일 진실 원천).
7. **`sanitizeUrl` `jdbc:` 접두사 미처리** — `URI("jdbc:postgresql://user:pw@host/db")`는 opaque URI로 파싱되어 `rawUserInfo == null` → 패스워드 그대로 로그 노출 (보안 결함). `jdbc:` 접두사 분리 후 hierarchical URI로 재파싱하도록 수정.
8. **`ensureSchema` 실패 로깅 부재** — 스키마 초기화 실패가 raw 예외로만 전파. `try/catch`로 감싸 컨텍스트 로그 후 재전파.
9. **공개 API KDoc/예제 보강** — `runIfLeader` / `runAsyncIfLeader` / `activeCount` / `availableSlots` / `state` 등 override 메서드 KDoc 부재 또는 단순. 예제 + `@throws` + 계약(null 반환, CancellationException 재전파) 명시. `Database` 확장함수 5개 모두 사용 예제 추가.

## 추가된 테스트

- `ExposedJdbcOptionsValidationTest` (10) — 옵션 init validation: lockOwner 255자 경계, maxLeaders 0/음수, RetryStrategy 변형 invariants
- `ExposedJdbcSchemaInitializerTest` (6) — sanitizeUrl 마스킹 (Postgres/MySQL/userinfo 없음/잘못된 URL/빈 userinfo) + ensureSchema 다중 스레드 동시성
- `ExposedJdbcLockTest`에 `isHeldByCurrentInstance` 4종 시나리오 추가 (보유/해제/만료/takeover)
- `ExposedJdbcGroupLockTest`에 slot 음수 init 검증 추가
- `ExposedJdbcLeaderElectionTest`에 async `failedFuture` 경로 (FAILED 이력 + 락 해제 + 재획득 가능)
- `ExposedJdbcLeaderGroupElectionTest`에 group async failedFuture + maxLeaders=1 경계 시맨틱 추가
- `RetryStrategyTest`에 baseDelayMs<2 거부 + 정상 생성 케이스로 갱신

## 테스트 결과

H2 / PostgreSQL / MySQL_V8 3DB 통과: 187 tests passing (ParameterizedTest로 DB별 실행).

## 변경 요약

- main: 12 file, +211 / -45
- test: 6 file (3 추가 + 3 수정), +306 / -20
- README.md / README.ko.md: VirtualThread 예제 정정 + 그룹 상태 조회 섹션 + RetryStrategy invariants 표 추가

## 후속 항목 (out-of-scope)

- `ExposedLeaderSchema` 캐시 키를 URL이 아닌 Database identity 기반으로 변경 가능성 (동일 URL/다른 schema/credentials 시나리오)
- `SchemaUtils.createMissingTablesAndColumns` deprecation 대응 (Exposed migration 권고)
