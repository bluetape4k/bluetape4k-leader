# Lessons Learned — Issue #50: Leader History Audit Contract (2026-05-14)

**관련 PR**: #214
**영향 모듈**: `leader-core`, `leader-exposed-core`, `leader-exposed-jdbc`, `leader-exposed-r2dbc`, `leader-redis-lettuce`, `leader-mongodb`, `leader-micrometer`, `leader-spring-boot`

---

## L1: `runCatching {}` 안에 suspend 호출 금지 — `CancellationException` 삼킴

### 문제
`ExposedR2DbcSuspendLeaderGroupElector.recordAcquired` / `recordFinished` 내부에서
`runCatching { suspendTransaction(...) { ... } }` 패턴을 사용했음.
`runCatching`은 `CancellationException`을 `Result.failure`로 감싸버려 structured concurrency가 깨짐.

### 교훈
- suspend 함수 안에서는 `runCatching {}` 절대 사용 금지
- 대신 `try/catch` + `catch (e: CancellationException) { throw e }` 패턴 사용
- `bluetape4k-patterns` 스킬의 **Suspend Lifecycle** 섹션 참조 필수

---

## L2: Spring Boot ObjectProvider 연결 — group elector에도 `historyRecorder` 필요

### 문제
Spring Boot auto-config에서 `historyRecorder`를 single elector에는 연결했으나
group elector(`MongoLeaderGroupElector`, `ExposedR2dbcSuspendLeaderGroupElector`)에 누락했음.
`MongoLeaderGroupElector`는 constructor 자체에 `historyRecorder` 파라미터가 없었음.

### 교훈
- 새로운 선택적 파라미터를 추가할 때 **모든 variant**(single/group, sync/suspend)에 일관 적용
- Spring config에서는 `ObjectProvider<T>.ifAvailable` 패턴으로 nullable 주입
- constructor에 파라미터가 없으면 v2-deferred stub `@Suppress("unused") private val x: T? = null` 패턴으로 시그니처 먼저 추가 후 연결

---

## L3: action 예외 계약 — null 반환 vs rethrow

### 문제
`ExposedJdbcLeaderElector.runIfLeader` action 예외 처리 계약이 설계 문서 간 불일치:
- `ExposedJdbcLeaderElector` 초기 spec (2026-05-02): rethrow
- Issue #50 spec (2026-05-12): null 반환 (ShedLock 호환)
- 최종 결정: **rethrow** (lock 미획득 시 null, action 예외 시 rethrow로 구분)

### 교훈
- "lock 미획득 → null, action 예외 → rethrow" 가 더 명확한 API
- 미래에 `Result<T>` 반환으로 컨텐션/예외를 타입으로 구분하는 것도 고려 (→ Issue #213)
- 계약 변경 시 KDoc `@throws`와 테스트를 동시에 수정하고 커밋 메시지에 기록

---

## L4: Testcontainers stale 컨테이너 충돌 — MySQL 테스트 실패 원인

### 문제
`leader-exposed-jdbc` MySQL 테스트가 `CommunicationsException`으로 실패.
원인: `bluetape4k-exposed` Docker Compose 컨테이너(`exposed_mysql8-mysql8-1`, port 3002)가
이미 떠 있는 상태에서 Testcontainers가 동일 이미지 재사용 시도 → 연결 충돌 또는 stale 연결.

### 교훈
- Testcontainers `reuse = true` 컨테이너는 JVM 종료 후에도 남음 (`close()` no-op)
- 다른 프로세스/Compose가 시작한 MySQL 컨테이너가 포트 충돌 유발 가능
- 테스트 실패 시 `docker ps | grep mysql` 먼저 확인 → stale 컨테이너 `docker stop + rm` 후 재실행
- `ShutdownQueue.register(this)` + `reuse = true`는 정리 안 됨 — 필요시 `reuse = false` 고려

---

## L5: token entropy 업그레이드 — 8자 → 22자 (Base58)

### 문제
`LettuceLock` 토큰이 8자 Base58로 너무 짧아 대규모 클러스터에서 충돌 가능.
또한 audit 로그에 전체 토큰이 노출되어 token-as-capability 패턴에서 보안 위험.

### 교훈
- 분산 락 token은 최소 22자 이상 (UUID 128bit 동등)
- audit 히스토리에는 `token.take(8) + "..."` redaction 처리
- 기존 Redis 키 호환성: 같은 key prefix 유지하면 토큰 길이 변경은 무관

---

## L6: Micrometer 조건부 recorder — `@ConditionalOnClass` + `ObjectProvider`

### 문제
`LeaderHistoryAutoConfiguration`에서 Micrometer 미존재 시 `MicrometerSafeLeaderHistoryRecorder` 빈이
생성되면서 `MeterRegistry` 미존재로 `NoSuchBeanDefinitionException` 발생 위험.

### 교훈
- `@ConditionalOnClass(name = ["io.micrometer...MeterRegistry", "...MicrometerXxx"])` 조건부 inner class
- 내부에서 `ObjectProvider<MeterRegistry>.ifAvailable` → null 시 plain 버전 fallback
- 두 클래스 모두 이름(문자열)으로 guard — `compileOnly` 타입은 직접 참조 금지
