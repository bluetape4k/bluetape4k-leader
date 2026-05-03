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

---

## 예정 작업

### #4 — 테스트 커버리지 확대

- [ ] leader-redis-lettuce: 네트워크 단절 시나리오
- [ ] leader-redis-redisson: HA(복수 JVM) 시나리오
- [ ] 통합 테스트: 다중 백엔드 혼합 사용

### #33 — leader-hazelcast (Hazelcast 백엔드)

- Hazelcast `ILock` / `FencedLock` 기반 구현
- `HazelcastLeaderElection`, `HazelcastSuspendLeaderElection`

### #10 — leader-micrometer (Micrometer 메트릭)

- 선출 횟수, 획득/실패 카운터, 보유 시간 히스토그램
- `MicrometerLeaderElectionDecorator` — 데코레이터 패턴

### #11 — leader-spring-boot3 + leader-spring-boot3-starter 🔬 자료조사 중

> 자료조사: [docs/research/spring-boot-integration.md](docs/research/spring-boot-integration.md)

**4개 모듈** (공통 + Boot 3 + Boot 4 각각 autoconfigure/starter):
- `leader-spring-boot-common`: ✅ **완료** (#27, #28 merged) — properties data class, abstract support
- `leader-spring-boot3`: `@AutoConfiguration`, `@ConditionalOnClass(name=...)` 조건부 Bean
- `leader-spring-boot3-starter`: 의존성 집합 전용 (코드 없음)

제공 기능:
- `LeaderElectionProperties` (`leader.wait-time`, `leader.lease-time`, `leader.group.*`)
- 백엔드별 조건부 Bean (Redisson, Lettuce, Exposed, MongoDB, Hazelcast)
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- (후순위) `@Leader` AOP, Actuator `/actuator/leader`

### #12 — leader-spring-boot4 + leader-spring-boot4-starter 🔬 자료조사 중

> 자료조사: [docs/research/spring-boot-integration.md](docs/research/spring-boot-integration.md)

**⚠️ Boot 4 주요 변경사항 대응 필요**:
- **autoconfigure 모듈 분산**: `spring-boot-autoconfigure` 단일 artifact → 기능별 분리
  - 패키지: `org.springframework.boot.autoconfigure.*` → `org.springframework.boot.*.autoconfigure`
  - `leader-spring-boot4`에서 필요한 autoconfigure artifact만 개별 선언
- **AOP → AspectJ**: `@Leader` AOP 구현 시 `spring-aspects` + AspectJ weaver 필요
  - Boot 3: `spring-aop` (CGLib), Boot 4: `spring-aspects` (AspectJ) — 분기 구현
- `leader-spring-boot-common` 공통 모듈로 중복 제거, Boot별 최소 구현만 유지

### #37 — leader-ktor (Ktor 통합) 🔬 자료조사 중

> 자료조사: [docs/research/ktor-integration.md](docs/research/ktor-integration.md)

- **옵션 C 검토**: Ktor Plugin DSL + lifecycle 연동 + `leaderScheduled()` 스케줄링 헬퍼
- `install(LeaderElectionPlugin) { leaderElection = ... }` DSL
- `application.leaderScheduled(period, lockName) { ... }` — `@Scheduled` 부재 보완
- Ktor 3.x 타겟 (`Application.monitor`, 빌트인 DI 3.2+)
- **선행 조건**: `leader-spring-boot3` 구현 형태 확정 후 대칭 설계

### #35 — CI/CD 파이프라인

- GitHub Actions: build, test, publish (SNAPSHOT/RELEASE)
- Testcontainers 서비스 연동
- Codecov 커버리지 리포팅

### #14 — README 정비

- ✅ 루트 README.md / README.ko.md 작성 (2026-04-29)
- ✅ leader-core README 작성 (2026-04-29)
- ✅ leader-redis-lettuce README 작성 (2026-04-29)
- ✅ leader-redis-redisson README 작성 (2026-04-29)
- [ ] 모듈 구현 완료 후 각 모듈 README 업데이트

### #36 — 예제 시나리오 모듈 (`leader-examples`)

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

## ShedLock 비교에서 도출한 추가 과제

> 참고: [docs/research/shedlock-vs-leader.md](docs/research/shedlock-vs-leader.md)

### #38 — `lockAtLeastFor` (최소 락 보유 시간)

ShedLock의 `lockAtLeastFor`에 해당. 작업이 너무 빨리 끝나도 락을 최소 시간 보유.  
노드 간 시계 오차가 있을 때 동일 작업이 다른 노드에서 즉시 재실행되는 문제 방지.

```kotlin
data class LeaderElectionOptions(
    val waitTime: Duration = 5.seconds,
    val leaseTime: Duration = 60.seconds,
    val minLeaseTime: Duration = Duration.ZERO,  // 추가 예정 — lockAtLeastFor 대응
)
```

- 대상 인터페이스: `LeaderElection`, `SuspendLeaderElection`, `LeaderGroupElection`
- 구현: 락 해제 전 `minLeaseTime` 잔여분 대기

### #39 — DB 서버 시간 기준 (`dbTime` 옵션)

Exposed/JDBC 백엔드 구현 시 앱 서버 시계 불일치 방지.  
ShedLock의 `usingDbTime()` 대응 — `SELECT NOW()` 기반으로 만료 시간 계산.

- 적용 모듈: `leader-exposed-jdbc`, `leader-exposed-r2dbc`
- `LeaderElectionOptions`에 `useDbTime: Boolean = false` 추가 검토

### #40 — 리더 이벤트 리스너 (`onElected` / `onRevoked`)

선출/해제 시점에 콜백 실행. 메트릭 수집, 알림 발송, 상태 전파에 활용.

```kotlin
interface LeaderElectionListener {
    fun onElected(lockName: String) {}
    fun onRevoked(lockName: String) {}
}

// 사용
election.addListener(object : LeaderElectionListener {
    override fun onElected(lockName: String) = metrics.increment("leader.elected", lockName)
})
```

- Micrometer 통합(`leader-micrometer` #10)과 연계
- Spring 이벤트(`ApplicationEventPublisher`)로도 노출 가능 (Boot 모듈)

### #41 — `@Leader` AOP 애노테이션 (Spring 통합)

ShedLock의 `@SchedulerLock` 대응. Spring Boot 모듈에서 AOP로 제공.

```kotlin
@Scheduled(cron = "0 0 2 * * *")
@Leader(name = "daily-settlement", leaseTime = "PT1H", waitTime = "PT5S")
fun dailySettlement() {
    settlementService.run()
}
```

- Boot 3: CGLib AOP (`spring-aop`)
- Boot 4: AspectJ (`spring-aspects`)
- 선행 조건: `leader-spring-boot3` (#11) 구현 완료 후

### #42 — 멀티테넌시 지원

테넌트별 락 네임스페이스 분리. SaaS 환경에서 테넌트 간 락 충돌 방지.

```kotlin
val tenantElection = election.forTenant(tenantId)
tenantElection.runIfLeader("report-job") { generateReport() }
// → 내부 락 이름: "tenant:{tenantId}:report-job"
```

- `LeaderElection.forTenant(tenantId: String): LeaderElection` 확장 함수로 구현 가능
- 별도 백엔드 변경 없이 락 이름 prefix로 대응

---

### #34 — leader-zookeeper (ZooKeeper 백엔드)

분산 환경에서 가장 신뢰성 높은 조정 서비스. Kafka 인프라를 쓰는 팀은 ZooKeeper가 이미 존재.  
Apache Curator `InterProcessMutex` / `InterProcessSemaphoreV2` 기반 구현.

```kotlin
val election = ZooKeeperLeaderElection(
    curatorFramework,
    LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 60.seconds)
)
election.runIfLeader("daily-job") { doWork() }
```

- `LeaderElection`: `InterProcessMutex` (단일 리더)
- `LeaderGroupElection`: `InterProcessSemaphoreV2` (멀티 리더)
- `SuspendLeaderElection`: `withContext(Dispatchers.IO)` 래핑
- 테스트: Testcontainers `zookeeper:3.9` (Apache 공식 이미지)
- **지원 확정** — 나머지 exotic 백엔드(DynamoDB, Cassandra, S3 등)는 지원 계획 없음

---

## 백로그 (우선순위 미정)

- gRPC/RSocket 기반 원격 리더 선출 클라이언트
- `@Leader` 어노테이션 AOP 지원 → #NEW-D로 이동
- 리더 이벤트 리스너 (`onElected`, `onRevoked`) → #NEW-C로 이동
- SpEL 락 이름 동적 표현식 (Spring Boot 모듈에서 `@Leader`와 연계)
- Kotlin Multiplatform 지원 (JS/Native 제외, JVM+Linux 타겟)

---

## 개발 규칙 메모

- `develop` 브랜치가 일상 작업 기준; `main`은 릴리즈 전용
- 모든 구현 작업은 `.worktrees/<branch>`에서 수행
- 이슈 1개 = PR 1개 = squash merge
- 커밋: 한국어 + conventional prefix (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`)
