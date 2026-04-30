# ShedLock vs bluetape4k-leader 비교

> 마지막 업데이트: 2026-04-30  
> **기능 추가/완료 시 이 문서의 백엔드·기능 비교 표를 반드시 업데이트할 것.**  
> 연관 문서: [WIP.md](../../WIP.md) — `ShedLock 비교에서 도출한 추가 과제` 섹션

---

## 핵심 철학 차이

| 항목 | ShedLock | bluetape4k-leader |
|---|---|---|
| **목적** | `@Scheduled` 작업 중복 실행 방지 | 범용 분산 리더 선출 |
| **경합 시 동작** | 즉시 skip (대기 없음) | `waitTime`만큼 대기 후 skip |
| **진입점** | AOP 애노테이션 (`@SchedulerLock`) | 명시적 API (`runIfLeader`) |
| **언어** | Java-first | Kotlin-first |
| **라이선스** | Apache 2.0 | (TBD) |
| **Spring 의존성** | 강결합 (AOP, `@Scheduled`) | 선택적 (별도 모듈) |

---

## 백엔드 지원 현황

| 백엔드 | ShedLock | bluetape4k-leader |
|---|---|---|
| Redis (Lettuce) | ✅ | ✅ |
| Redis (Redisson) | ✅ | ✅ |
| Redis (Jedis) | ✅ | ❌ |
| JDBC (JdbcTemplate) | ✅ | ❌ |
| Exposed (JDBC) | ✅ | 🚧 [#21](https://github.com/bluetape4k/bluetape4k-leader/issues/21) |
| Exposed (R2DBC) | ✅ | 🚧 [#22](https://github.com/bluetape4k/bluetape4k-leader/issues/22) |
| jOOQ | ✅ | ❌ |
| MongoDB | ✅ | 🚧 [#8](https://github.com/bluetape4k/bluetape4k-leader/issues/8) |
| Hazelcast | ✅ | 🚧 [#33](https://github.com/bluetape4k/bluetape4k-leader/issues/33) |
| ZooKeeper | ✅ | 🚧 [#34](https://github.com/bluetape4k/bluetape4k-leader/issues/34) (Curator) |
| DynamoDB | ✅ | 🚫 계획 없음 |
| Cassandra | ✅ | 🚫 계획 없음 |
| Elasticsearch | ✅ | 🚫 계획 없음 |
| Etcd | ✅ | 🚫 계획 없음 |
| In-Memory (local) | ✅ | ✅ |
| S3 / GCS / Firestore | ✅ | 🚫 계획 없음 |
| NATS JetStream | ✅ | 🚫 계획 없음 |

---

## 실행 모델 지원

| 모델 | ShedLock | bluetape4k-leader |
|---|---|---|
| Blocking (동기) | ✅ | ✅ `LeaderElection` |
| CompletableFuture | ❌ | ✅ `AsyncLeaderElection` |
| Kotlin Coroutine (suspend) | ❌ | ✅ `SuspendLeaderElection` |
| Virtual Thread | ❌ | ✅ `VirtualThreadLeaderElection` |
| R2DBC (reactive streams) | 🔬 실험적 | 🚧 `leader-exposed-r2dbc` |

---

## 기능 비교

| 기능 | ShedLock | bluetape4k-leader |
|---|---|---|
| 단일 리더 선출 | ✅ | ✅ `LeaderElection` |
| 멀티 리더 (semaphore) | ❌ | ✅ `LeaderGroupElection` |
| `lockAtLeastFor` (최소 보유) | ✅ | 🚧 [#38](https://github.com/bluetape4k/bluetape4k-leader/issues/38) |
| `lockAtMostFor` (최대 보유) | ✅ | ✅ `leaseTime` |
| `waitTime` (획득 대기) | ❌ (즉시 skip) | ✅ |
| AOP 애노테이션 (`@Leader`) | ✅ `@SchedulerLock` | 🚧 [#41](https://github.com/bluetape4k/bluetape4k-leader/issues/41) |
| SpEL 락 이름 표현식 | ✅ | ❌ |
| 멀티테넌시 | ✅ | 🚧 [#42](https://github.com/bluetape4k/bluetape4k-leader/issues/42) |
| Micrometer 메트릭 | ✅ (Spring 통합) | 🚧 [#10](https://github.com/bluetape4k/bluetape4k-leader/issues/10) |
| Actuator 엔드포인트 | ✅ | 🚧 [#11](https://github.com/bluetape4k/bluetape4k-leader/issues/11)/[#12](https://github.com/bluetape4k/bluetape4k-leader/issues/12) |
| 리더 이벤트 리스너 | ❌ | 🚧 [#40](https://github.com/bluetape4k/bluetape4k-leader/issues/40) |
| Spring Boot AutoConfiguration | ✅ | 🚧 [#11](https://github.com/bluetape4k/bluetape4k-leader/issues/11)/[#12](https://github.com/bluetape4k/bluetape4k-leader/issues/12) |
| Ktor 통합 | ❌ | 🚧 [#37](https://github.com/bluetape4k/bluetape4k-leader/issues/37) |
| Micronaut 통합 | ✅ | ❌ |
| 클록 동기화 (`usingDbTime()`) | ✅ (JDBC) | 🚧 [#39](https://github.com/bluetape4k/bluetape4k-leader/issues/39) |

---

## ShedLock 장점 (bluetape4k-leader가 부족한 부분)

### 1. 백엔드 생태계
ShedLock은 20+ 백엔드 지원. bluetape4k-leader는 Redis 2종 + Exposed/MongoDB/Hazelcast 구현 중.

### 2. `@SchedulerLock` AOP
```java
@Scheduled(cron = "0 */15 * * * *")
@SchedulerLock(name = "myTask", lockAtMostFor = "14m", lockAtLeastFor = "14m")
void scheduledTask() { ... }
```
기존 코드 변경 최소화. bluetape4k-leader는 명시적 API 호출 필요.

### 3. `lockAtLeastFor`
작업이 너무 빨리 끝나도 락을 최소 시간 유지. 노드 간 시계 오차 보호에 유효.

### 4. SpEL 락 이름 동적 지정
```java
@SchedulerLock(name = "'task-' + #region")
```

### 5. `usingDbTime()` — DB 서버 시간 기준 락
앱 서버 시계 불일치 문제를 DB 시간으로 해결. 특히 JDBC 백엔드에서 중요.

### 6. 성숙도 및 커뮤니티
수년간 대규모 프로덕션 검증. bluetape4k-leader는 초기 단계.

---

## bluetape4k-leader 장점 (ShedLock 대비 우위)

### 1. Kotlin 실행 모델 완전 지원
```kotlin
// 코루틴 네이티브
suspend fun work() = election.runIfLeader("job") { doSuspend() }

// 가상 스레드 (JVM 21+)
val result = vtElection.runIfLeader("job") { blockingWork() }

// CompletableFuture
val future = asyncElection.runAsyncIfLeader("job") { compute() }
```

### 2. 멀티 리더 (LeaderGroupElection)
```kotlin
val options = LeaderGroupElectionOptions(maxLeaders = 3)
election.runIfLeader("batch-job") { processChunk() }
```
ShedLock은 단일 리더만 지원.

### 3. 프레임워크 독립
Spring 없이 Ktor, Quarkus, 순수 JVM에서 동작.

### 4. `waitTime` 제어
경합 시 최대 N초 대기 후 판단. 짧은 작업에서 리더 획득 기회 증가.

### 5. 인터페이스 타입 안전 분리
실행 모델별로 별도 인터페이스 — 컴파일 타임에 의도 명확.

---

## 미구현 기능 — 추가 계획

아래 항목은 ShedLock 비교에서 도출한 bluetape4k-leader 부족 기능.  
WIP.md 이슈와 연결.

| 기능 | 우선순위 | GitHub 이슈 |
|---|---|---|
| `lockAtLeastFor` (최소 락 보유) | 중 | [#38](https://github.com/bluetape4k/bluetape4k-leader/issues/38) |
| `@Leader` AOP 애노테이션 | 중 | [#41](https://github.com/bluetape4k/bluetape4k-leader/issues/41) |
| SpEL 락 이름 표현식 | 하 | 백로그 |
| 멀티테넌시 | 하 | [#42](https://github.com/bluetape4k/bluetape4k-leader/issues/42) |
| DB 서버 시간 기준 (`dbTime`) | 중 | [#39](https://github.com/bluetape4k/bluetape4k-leader/issues/39) |
| 리더 이벤트 리스너 (`onElected`, `onRevoked`) | 중 | [#40](https://github.com/bluetape4k/bluetape4k-leader/issues/40) |
| ZooKeeper 백엔드 | 중 | [#34](https://github.com/bluetape4k/bluetape4k-leader/issues/34) |
| DynamoDB / Cassandra / S3 등 | — | 🚫 지원 안 함 |

---

## 선택 기준

| 상황 | 추천 |
|---|---|
| Spring `@Scheduled` + 다양한 DB 백엔드 | ShedLock |
| Kotlin 코루틴/가상 스레드 + Redis | bluetape4k-leader |
| 멀티 리더 (semaphore 기반) | bluetape4k-leader |
| Spring 없는 순수 Kotlin 서비스 | bluetape4k-leader |
| Ktor 서버 | bluetape4k-leader (🚧) |
| 레거시 Java 서비스 | ShedLock |
