# WIP — 작업 중 (Work In Progress)

> 현재 진행 중이거나 예정된 작업을 추적합니다.  
> 완료된 항목은 CHANGELOG.md로 이동합니다.

---

## 현재 단계: 0.1.0-SNAPSHOT

| 이슈 | 제목 | 상태 | PR |
|------|------|------|----|
| #1/#15 | runIfLeader() 반환 타입 T?로 변경 (skip 동작) | ✅ 완료 | merged |
| #2 | LettuceLock/Semaphore 이식 및 bluetape4k-lettuce 의존성 제거 | ✅ 완료 | merged |
| #3 | leader-redis-redisson 테스트 자립화 (AbstractRedissonLeaderTest) | ✅ 완료 | #17 merged |
| #4/#5/#6 | leader-core/lettuce/redisson 컴파일 + 테스트 통과 | ✅ 완료 | #45 merged |
| #7/#24 | leader-exposed 모듈 분리 (core/jdbc/r2dbc) — 구조 생성 | ✅ 완료 | #24 merged |
| #23 | leader-exposed-core — 공통 스키마 구현 | ✅ 완료 | #52/#62 포함 |
| #21 | leader-exposed-jdbc — Exposed JDBC 분산 락 구현 | ✅ 완료 | #52 merged |
| #22 | leader-exposed-r2dbc — Exposed R2DBC 코루틴 구현 | ✅ 완료 | #62 merged |
| #59/#60/#61 | ExposedJdbcGroupLock: isHeldByCurrentInstance, tryLock Boolean?, KLoggingChannel | ✅ 완료 | #63 merged |
| #9 | leader-hazelcast — IMap 토큰 락 기반 분산 리더 선출 | ✅ 완료 | merged |
| #13/#35 | GitHub Actions CI/CD 파이프라인 구성 | ✅ 완료 | #19/#44 merged |
| #14 | README.md / README.ko.md 전 모듈 작성 | ✅ 완료 | #18 merged |
| #25 | 코루틴 취소 안전성 강화 + 옵션/상태 검증 | ✅ 완료 | #25 merged |
| #27 | leader-spring-boot-common — Boot 버전 독립 공통 모듈 | ✅ 완료 | #28 merged |
| #29/#31/#32 | StrategicLeaderElection (leader-core + Redis 백엔드) | ✅ 완료 | merged |
| #8 | leader-mongodb — MongoDB findOneAndUpdate + TTL 기반 분산 락 | ✅ 완료 | #46 merged |
| #11/#12 | leader-spring-boot3 / leader-spring-boot4 AutoConfiguration | ✅ 완료 | merged |
| **#41** | **@LeaderElection, @LeaderGroupElection AOP 애노테이션 (Spring Boot 3/4)** | **✅ 완료** | **#86 merged** |
| #75/#101 | leader-aop Micrometer metrics 통합 | ✅ 완료 | #84 merged |
| #104/#105 | Spring Boot 3 드랍 — leader-spring-boot 단일 모듈 통합 | ✅ 완료 | merged |
| #76 | spring-boot 디렉토리 재구조화 | ✅ 완료 | #104/#105로 해소 |
| #100 | LeaderElection/LeaderGroupElection → LeaderElector/LeaderGroupElector rename | ✅ 완료 | #106 merged |
| #103 | feat: LeaderAopProperties Metrics 중첩 클래스 추가 (IDE 자동완성) | ✅ 완료 | #111 merged |
| #102 | feat: LeaderMicrometerHealthAutoConfiguration 추가 | ✅ 완료 | #112 merged |
| #87 | test: Boot4 Freefair CTW double-fire 방지 검증 테스트 | ✅ 완료 | #113 merged |
| #85 | feat: LeaderRunResult sealed interface — elected vs skipped 구분 | ✅ 완료 | #114 merged |
| #81 | feat: FAIL_OPEN_RUN failureMode 구현 | ✅ 완료 | #116 merged |
| #84 | feat: 메타 어노테이션 (@AliasFor) 지원 | ✅ 완료 | #117 merged |
| #78 | feat: 클래스/패키지 레벨 @LeaderElectionBackend 메타 어노테이션 | ✅ 완료 | #118 merged |
| #82 | feat: SpEL TemplateParserContext 혼합 표현식 지원 | ✅ 완료 | #119 merged |

---

## 이슈 의존 관계

```
#41 (AOP) ✅ ─── #75 (Micrometer) ✅ ──┬── #85 (elected/skipped SPI) ✅
                                        ├── #102 (HealthAutoConfiguration) ✅
                                        └── #103 (MetricsProperties) ✅

#41 ✅ ────────────────────────────────┬── #94 (bug: failureMode bypass) ← P0
                                       ├── #95 (test: LeaderGroupElectionAspect) ← P0
                                       ├── #96 (test: BPP suspend/Mono 분기) ← P0
                                       ├── #97 (test: SpEL null + placeholder) ← P0
                                       ├── #87 (Boot4 double-fire 검증)
                                       ├── #81 (FAIL_OPEN_RUN + LeaderResult) ✅
                                       └── #80 sub-issues ─── #88 → #89 → #90 → #91 → #92

독립 기능 (병렬 가능):
  #82 (SpEL TemplateParserContext)
  #84 (메타 어노테이션 @AliasFor)
  #78 (@LeaderElectionBackend 클래스/패키지 레벨)

보류:
  #83 (Result<T> 반환) — 파괴적 변경, 0.1.0 출시 이후 별도 논의

백로그 (낮은 우선순위):
  #40 (이벤트 리스너) → #75 이후 자연스럽게 연동 가능
  #68 (Election 상태 조회 API)
  #69 (SpEL 동적 락 이름) ─── #82 와 합칠 수 있음
  #72 (LeaderGroup leaderId 지원)
  #73 (watchdog / lease auto-extend)
  #77 (minLeaseTime 백엔드 TTL 위임)
  #79 (LockExtender / LockAssert)
  #38/#39 (lockAtLeastFor, useDbTime)
  #50 (리더 선출 이력 감사)
```

---

## 실행 순서

### P0 — 버그 수정 (즉시, 병렬 가능)

| 이슈 | 제목 | 선행 조건 |
|------|------|----------|
| **#94** | **fix: factory.create() pre-try I/O가 failureMode + LeaderElectionException wrap 우회** | ✅ #116에 포함 |
| #97 | test: SpelExpressionEvaluator null 결과 + `${...}` placeholder 경로 미테스트 | ✅ #116에 포함 |
| #96 | test: LeaderAnnotationValidatorBeanPostProcessor suspend/Mono/Flux/Flow/@Aspect skip 미테스트 | ✅ #116에 포함 |
| #95 | test: LeaderGroupElectionAspect 전체 테스트 부재 | ✅ #116에 포함 |

### P1 — #75 완료 이후 연쇄 (지금 가능)

| 이슈 | 제목 | 선행 조건 | 우선순위 |
|------|------|----------|---------|
| **#85** | **feat: 백엔드 SPI elected vs skipped 명확 구분 (metrics 정확도)** | #75 ✅ | ✅ 완료 |
| **#81** | **feat: FAIL_OPEN_RUN failureMode 구현** | #41 ✅ | **✅ 완료** |

### P2 — 독립 기능 확장 (병렬 가능)

| 이슈 | 제목 | 선행 조건 |
|------|------|----------|
| **#82** | **feat: SpEL TemplateParserContext 혼합 표현식 지원** | #41 ✅ | **✅ 완료 — PR #119 merged** |
| **#84** | **feat: 메타 어노테이션 (@AliasFor) 지원** | #41 ✅ | **✅ 완료 — PR #117 merged** |
| **#78** | **feat: 클래스/패키지 레벨 @LeaderElectionBackend 메타 어노테이션** | #41 ✅ | **✅ 완료 — PR #118 merged** |

### P3 — Wave 5 suspend/Mono 지원 (순차 의존, 난이도 높음)

| 이슈 | 제목 | 선행 조건 |
|------|------|----------|
| #88 | feat: SuspendLeaderElectorFactory SPI 정의 + Local 구현 | #41 ✅ |
| #89 | feat: SuspendLeaderElectorFactory 백엔드 구현 (Lettuce/Redisson/Mongo/ExposedR2dbc) | #88 |
| #90 | feat: Aspect suspend 반환 타입 분기 (Spring AOP + Kotlin Coroutines) | #89 |
| #91 | feat: Aspect Mono<T> 반환 타입 분기 | #90 |
| #92 | feat: LeaderElectionInfo CoroutineContext element + Reactor context propagation | #91 |

> **⚠️ suspend/Mono 지원 난이도**: Spring AOP CGLib 기반에서 suspend/Mono 반환 타입 인터셉트는  
> 기술적 난관이 크다. #88부터 단계적 검증 후 진행.

---

## 백로그 (우선순위 미정)

| 이슈 | 제목 | 비고 |
|------|------|------|
| #40 | 리더 이벤트 리스너 (onElected / onRevoked) | #75 이후 자연 연동 |
| #68 | Election 상태 조회 API (시작 시각, 남은 slot 등) | #41 이후 |
| #69 | SpEL 기반 동적 락 이름 표현식 | #82 와 통합 검토 |
| #72 | @LeaderGroupElection leaderId 지원 (Group API 변경) | 파괴적 변경 — 별도 마이너 |
| #73 | watchdog / lease auto-extend (split-brain 방지) | 복잡도 높음 |
| #77 | minLeaseTime 백엔드 TTL 위임 + lockAtLeastFor 검증 | #38 흡수 |
| #79 | LockExtender / LockAssert 등가 API | ShedLock 기능 대응 |
| #38 | lockAtLeastFor (최소 락 보유 시간) | #77 로 흡수 검토 |
| #39 | useDbTime — DB 서버 시간 기준 락 | Exposed 백엔드 한정 |
| #50 | 공통 리더 선출 이력 감사 계약 | 낮음 |
| #83 | runIfLeader {} 반환을 Result<T>로 변경 | 파괴적 변경 — 0.1.0 출시 이후 |
| #10 | leader-micrometer 독립 모듈 (InstrumentedLeaderElection) | #75 로 방향 전환 검토 |
| #34 | leader-zookeeper (Apache Curator 기반) | 낮음 |
| #36 | leader-examples (실무 시나리오 5종) | 기능 안정 후 |
| #37 | leader-ktor 통합 (Ktor 3.x Plugin DSL) | Spring 완료 후 |
| #42 | 멀티테넌시 지원 (테넌트별 락 네임스페이스) | 낮음 |

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

## 개발 규칙 메모

- `develop` 브랜치가 일상 작업 기준; `main`은 릴리즈 전용
- 모든 구현 작업은 `.worktrees/<branch>`에서 수행
- 이슈 1개 = PR 1개 = squash merge
- 커밋: 한국어 + conventional prefix (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`)
