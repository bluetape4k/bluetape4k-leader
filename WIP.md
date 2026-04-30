# WIP — 작업 중 (Work In Progress)

> 현재 진행 중이거나 예정된 작업을 추적합니다.  
> 완료된 항목은 CHANGELOG.md로 이동합니다.

---

## 현재 단계: 0.1.0-SNAPSHOT

| 이슈 | 제목 | 상태 | PR |
|------|------|------|----|
| #2 | LettuceLock/Semaphore 이식 및 bluetape4k-lettuce 의존성 제거 | ✅ 완료 | merged |
| #3 | leader-redis-redisson 테스트 자립화 (AbstractRedissonLeaderTest) | ✅ 완료 | #17 merged |
| #15 | runIfLeader() 반환 타입 T?로 변경 (skip 동작) | ✅ 완료 | merged |
| #27 | leader-spring-boot-common — Boot 버전 독립 공통 모듈 | ✅ 완료 | #28 merged |
| #29 | 플러그형 선출 전략 (StrategicLeaderElection) — leader-core pilot | 🔄 진행 중 | feat/strategic-leader-election |

---

## 예정 작업

### #29 — 플러그형 선출 전략 (StrategicLeaderElection) 🔄 진행 중

> Spec: [docs/spec/strategic-leader-election.md](docs/spec/strategic-leader-election.md)
> Plan: [docs/plan/strategic-leader-election.md](docs/plan/strategic-leader-election.md)

- ✅ leader-core pilot 구현: CandidateInfo, ElectionStrategy, CandidateScorer, Local 구현체
- ✅ 내장 전략: FifoElectionStrategy, RandomElectionStrategy, ScoredElectionStrategy
- ✅ 내장 Scorer: IdleTimeScorer, SuccessRateScorer, RecentSuccessScorer, WeightedScorer
- [ ] Redis(Redisson/Lettuce) 백엔드 구현 (CandidateRegistry — sorted set/hash + TTL)
- [ ] Exposed/MongoDB 백엔드 구현
- [ ] 분산 epoch seed 공유 (RandomElectionStrategy 분산 결정론)

---

### #4 — 테스트 커버리지 확대

- [ ] leader-core: `LocalLeaderElection` 엣지 케이스 (waitTime 만료, 동시성)
- [ ] leader-redis-lettuce: 네트워크 단절 시나리오
- [ ] leader-redis-redisson: HA(복수 JVM) 시나리오
- [ ] 통합 테스트: 다중 백엔드 혼합 사용

### #7 → #21/#22/#23 — leader-exposed 모듈 분리 (완료)

- ✅ `leader-exposed-core`: 공통 스키마 정의 (PR #24 merged)
- ✅ `leader-exposed-jdbc`: JDBC 블로킹 구현 (모듈 생성 완료)
- ✅ `leader-exposed-r2dbc`: R2DBC 코루틴 구현 (모듈 생성 완료)

### #21 — leader-exposed-jdbc 구현

- PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` 기반 행 수준 잠금
- `ExposedJdbcLeaderElection` + HikariCP 커넥션 풀
- 테스트: Testcontainers PostgreSQL

### #22 — leader-exposed-r2dbc 구현

- R2DBC + Coroutines 기반 비동기 리더 선출
- `ExposedR2dbcSuspendLeaderElection`
- 테스트: Testcontainers PostgreSQL (r2dbc-postgresql)

### #23 — leader-exposed-core 스키마 구현

- `LeaderTable` Exposed Table 정의 (공통 스키마)
- JDBC/R2DBC 양쪽에서 재사용 가능한 DDL 생성 유틸

### #8 — leader-mongodb (MongoDB 백엔드)

- MongoDB `findOneAndUpdate` + TTL 인덱스 기반 잠금
- `MongoLeaderElection`, `MongoSuspendLeaderElection`
- 테스트: Testcontainers MongoDB

### #9 — leader-hazelcast (Hazelcast 백엔드)

- Hazelcast `ILock` / `FencedLock` 기반 구현
- `HazelcastLeaderElection`, `HazelcastSuspendLeaderElection`

### #10 — leader-micrometer (Micrometer 메트릭)

- 선출 횟수, 획득/실패 카운터, 보유 시간 히스토그램
- `MicrometerLeaderElectionDecorator` — 데코레이터 패턴

### #11 — leader-spring-boot3 (Spring Boot 3 자동 구성)

- `@EnableLeaderElection` 어노테이션
- 백엔드 자동 감지 (Redis/MongoDB/Exposed/Hazelcast)
- Spring Boot 3.x + Coroutines 지원

### #12 — leader-spring-boot4 (Spring Boot 4 자동 구성)

- Spring Boot 4.x 지원 (`leader-spring-boot3` 기반 분기)

### #13 — CI/CD 파이프라인

- GitHub Actions: build, test, publish (SNAPSHOT/RELEASE)
- Testcontainers 서비스 연동
- Codecov 커버리지 리포팅

### #14 — README 정비

- ✅ 루트 README.md / README.ko.md 작성 (2026-04-29)
- ✅ leader-core README 작성 (2026-04-29)
- ✅ leader-redis-lettuce README 작성 (2026-04-29)
- ✅ leader-redis-redisson README 작성 (2026-04-29)
- [ ] 모듈 구현 완료 후 각 모듈 README 업데이트

### #25 — 예제 시나리오 모듈 (`leader-examples`)

실무 시나리오 5종을 다양한 백엔드로 구현한 실행 가능한 예제 모음.  
각 예제는 독립적으로 실행 가능하며 Testcontainers로 외부 의존성을 자동 프로비저닝한다.

---

## 예제 시나리오

### E1 — 분산 배치 스케줄러 (Redis-Lettuce)

**문제**: 동일 배치 서비스가 3대 이상 배포되었을 때 새벽 정산 Job이 중복 실행되는 문제  
**해법**: `LeaderElection.runIfLeader { }` 로 리더 노드만 Job 실행, 나머지는 즉시 skip

```kotlin
// Spring @Scheduled 환경
@Scheduled(cron = "0 0 2 * * *")
fun dailySettlement() {
    leaderElection.runIfLeader {
        settlementService.runDailyJob()
    } ?: log.info("리더 아님 — 이번 배치는 다른 노드가 처리")
}
```

- 백엔드: `leader-redis-lettuce`
- 핵심 포인트: `runIfLeader()` 반환 `null` = 선출 실패, 예외 아님
- 시연: 3개 JVM 인스턴스 + Redis 1대, Job 실행 로그로 단일 실행 확인

---

### E2 — 롤링 배포 시 DB 마이그레이션 게이트 (Exposed-JDBC)

**문제**: Kubernetes 롤링 배포 중 N개 Pod가 동시 기동할 때 Flyway 마이그레이션이 중복 충돌  
**해법**: 리더 Pod만 마이그레이션 실행, 나머지는 리더 완료까지 대기 후 합류

```kotlin
fun onApplicationReady() {
    val migrated = leaderElection.runIfLeader(waitTime = 30.seconds) {
        flyway.migrate()
    }
    if (migrated == null) {
        awaitMigrationComplete() // 폴링 또는 DB 상태 확인
    }
}
```

- 백엔드: `leader-exposed-jdbc` (PostgreSQL)
- 핵심 포인트: `waitTime` 으로 리더 선출 최대 대기 시간 제어
- 시연: Docker Compose로 동일 이미지 3개 병렬 기동, 마이그레이션 1회 실행 확인

---

### E3 — 외부 Webhook 이벤트 폴러 (MongoDB)

**문제**: 외부 결제 PG의 Webhook을 여러 인스턴스가 동시에 폴링하면 중복 처리 발생  
**해법**: 리더만 폴링 루프를 실행, 나머지는 내부 이벤트 버스 소비자로만 동작

```kotlin
// Coroutine 기반 suspend 버전
suspend fun startPollingIfLeader() {
    suspendLeaderElection.runIfLeader {
        while (isActive) {
            val events = webhookClient.poll()
            events.forEach { eventBus.publish(it) }
            delay(5.seconds)
        }
    }
}
```

- 백엔드: `leader-mongodb` (TTL 인덱스 + `findOneAndUpdate`)
- 핵심 포인트: `SuspendLeaderElection` + 코루틴 취소 연동
- 시연: Testcontainers MongoDB + MockWebServer, 3 인스턴스 중 폴링 1건 확인

---

### E4 — 분산 캐시 파티션 워머 (Hazelcast + LeaderGroup)

**문제**: 글로벌 상품 카탈로그를 5개 리전 캐시에 나눠 저장할 때,  
각 리전 캐시의 만료 갱신을 누가 담당할지 충돌  
**해법**: `LeaderGroup`으로 리전별 독립 리더 선출 → 각 리더가 자기 파티션만 갱신

```kotlin
val leaderGroup = HazelcastLeaderGroup(hazelcastInstance)

regions.forEach { region ->
    launch {
        leaderGroup.forKey(region).runIfLeader {
            cacheWarmer.warmRegion(region)
        }
    }
}
```

- 백엔드: `leader-hazelcast` (`FencedLock` 기반)
- 핵심 포인트: `LeaderGroup.forKey(partitionKey)` — 파티션별 독립 선출
- 시연: 5개 리전 × 3개 인스턴스 = 15 경합, 리전당 정확히 1 워머 실행 확인

---

### E5 — 멀티테넌트 실시간 집계 (Exposed-R2DBC + LeaderGroup)

**문제**: SaaS 서비스에서 테넌트(조직)별 실시간 사용량 집계를 스케줄링할 때,  
동일 테넌트 집계가 여러 워커에서 중복 실행되어 통계 오염  
**해법**: `LeaderGroup.forKey(tenantId)` 로 테넌트별 독립 리더가 집계 담당

```kotlin
// R2DBC 비동기 집계
suspend fun aggregateTenant(tenantId: TenantId) {
    leaderGroup.forKey(tenantId.value).runIfLeader {
        r2dbcAggregator.computeAndStore(tenantId)
    } ?: return // 이 워커는 해당 테넌트의 리더 아님
}
```

- 백엔드: `leader-exposed-r2dbc` (PostgreSQL R2DBC)
- 핵심 포인트: `LeaderGroup` + `SuspendLeaderElection` 조합, 테넌트 격리 보장
- 시연: 100 테넌트 × 5 워커, 테넌트별 집계 정확히 1회 실행 + Micrometer 카운터 검증

---

## 백로그 (우선순위 미정)

- gRPC/RSocket 기반 원격 리더 선출 클라이언트
- 리더 이벤트 리스너 (`onElected`, `onRevoked`)
- `@Leader` 어노테이션 AOP 지원 (Spring 연동)
- Kotlin Multiplatform 지원 (JS/Native 제외, JVM+Linux 타겟)

---

## 개발 규칙 메모

- `develop` 브랜치가 일상 작업 기준; `main`은 릴리즈 전용
- 모든 구현 작업은 `.worktrees/<branch>`에서 수행
- 이슈 1개 = PR 1개 = squash merge
- 커밋: 한국어 + conventional prefix (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`)
