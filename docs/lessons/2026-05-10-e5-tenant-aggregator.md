# Lessons — E5 examples/tenant-aggregator

날짜: 2026-05-10
범위: Issue #158 — examples/tenant-aggregator (Exposed R2DBC + multi-tenant leader election)

## 컨텍스트

E5는 Epic #36 examples 시리즈의 다섯 번째 모듈로, **멀티테넌트 환경에서 테넌트별로 독립된 집계 작업**을 실행해야 하는 상황을 시연한다. 사용자 시나리오:

- 테넌트 N개 (예: tenant-A, tenant-B, tenant-C)
- 인스턴스 M개 (Kubernetes pod 등)
- 각 테넌트의 집계 코루틴은 동시에 정확히 1 인스턴스에서만 실행되어야 함
- 집계는 long-running polling loop (E1 batch-scheduler 의 1회 실행과 다름)

기술 스택은 leader-exposed-r2dbc (suspend coroutine + R2DBC 비동기 I/O).

## L1 — 테넌트별 독립 lockName + LeaderGroup 미사용 (E4 와 동일)

`SuspendLeaderGroupElector` 는 단일 lockName 의 maxLeaders 슬롯을 공유한다. "tenant T 가 슬롯 k 에 배정되도록" 호출자가 강제할 수 없으므로, 테넌트 ↔ 슬롯 매핑이 불확정이고 결국 **둘 다 슬롯을 받지 못해 테넌트 T 가 영영 polling 안 되는** 시나리오가 가능하다.

해결: 테넌트마다 별도 lockName (`"${lockNamePrefix}-${tenantId}"`)으로 단일 leader-election 을 N 번 수행한다. "tenant T 에 대해서는 정확히 1 인스턴스" 계약을 직접 표현 가능. E4 cache-warmer 와 동일한 결론이며, 본 모듈은 long-running coroutine 으로 확장한 형태.

## L2 — 테넌트별 child coroutine + supervisorScope

테넌트 N개 polling 을 단일 루프에서 순차 처리하면 한 테넌트의 락 획득 대기가 다른 테넌트의 polling 주기를 좁힌다. 해결: `supervisorScope { tenants.forEach { launch { tenantLoop(it) } } }` 으로 테넌트마다 독립 child coroutine 을 띄운다.

`coroutineScope` 가 아닌 `supervisorScope` 를 쓰는 이유는 한 테넌트의 예기치 못한 예외 (electorFactory throw 등) 가 다른 테넌트 루프를 죽이지 않게 하기 위함이다. coroutine cancellation 자체는 여전히 부모 → 자식 전파된다.

## L3 — Aggregate 함수 예외 격리 (poison 방지)

`aggregateFunction` 에서 발생한 예외가 polling 루프 자체를 종료시키면, 일시적 실패가 영구 정지로 이어진다 (poison loop). 해결: `runAggregate(tenantId)` 헬퍼에서 예외를 catch 하고 log warn 후 swallow — 단 `CancellationException` 은 즉시 re-throw 하여 cancellation 무결성 유지.

`runIfLeader` 자체 (백엔드 장애로 인한 R2DBC 예외 등) 도 동일 정책: log warn 후 다음 사이클 재시도. 결국 두 단계 격리:
1. `runAggregate` — 사용자 함수 예외
2. tenantLoop 의 catch — 락 인프라 예외

두 경우 모두 다음 사이클로 진행한다.

## L4 — Elector 1회 생성 + suspend factory

각 사이클마다 elector 를 새로 만들면 `ExposedR2DbcSuspendLeaderElector(db, options)` 의 `ensureSchema` 등 무거운 초기화가 매번 반복된다. 해결: `tenantLoop` 진입 시 elector 1회 생성 후 동일 instance 의 `runIfLeader` 를 사이클마다 재호출.

`electorFactory` 시그니처는 suspend (Mongo 의 `MongoSuspendLeaderElector` 도 suspend factory 패턴) — 테스트 fake 주입성을 위해 `(lockName, LeaderElectionOptions) -> SuspendLeaderElector` 로 단순화.

## L5 — stopGracefully 패턴 (E3 webhook-poller 동일)

`rootJob.cancelAndJoin()` 을 `withTimeoutOrNull(timeout)` 로 감싸서, timeout 초과 시 강제 cancel 하여 호출자가 무한 대기하지 않도록 보장. `pollerJob = null` finally 처리는 두 번 호출 시 idempotent.

진행 중인 `aggregateFunction` 호출은 cancel 되며, `runIfLeader` 가 보유한 락은 elector 의 finally 블록에서 자동 해제 → 락 lease 만료 후 차순위 인스턴스가 인계 (at-least-once 보장).

## L6 — `ExposedR2dbcSchemaInitializer` 가 internal

테스트 베이스 작성 시 `ExposedR2dbcSchemaInitializer.ensureSchema(db)` 를 직접 호출하려 했으나 `internal` 가시성으로 인해 다른 모듈에서 접근 불가. 해결: throwaway `ExposedR2DbcSuspendLeaderElector(...)` 를 1회 생성하여 companion `suspend operator fun invoke` 가 호출하는 ensureSchema 를 간접 트리거.

향후 leader-exposed-r2dbc 가 `ensureSchema` 를 public 으로 승격시키면 더 깔끔해진다.

## L7 — H2 R2DBC + Testcontainers PostgreSQL 병행 테스트

`AbstractTenantAggregatorTest.enableDialects()` 가 `LEADER_TEST_DB` 환경 변수에 따라 dialect 를 필터링. 미설정이면 H2 + PostgreSQL 모두 실행. CI 에서는 환경 변수 미설정으로 두 dialect 모두 검증.

H2 in-memory R2DBC URL 은 `r2dbc:h2:mem:///<unique>;MODE=MySQL;DB_CLOSE_DELAY=-1` — `MODE=MySQL` 는 `insertIgnore` 등 lock SQL 호환을 위해 필수, `DB_CLOSE_DELAY=-1` 은 connection close 시 in-memory DB 가 사라지지 않도록.

## L8 — Workflow 누락 방지 체크리스트 (E4 lesson L4 강화)

E4 cache-warmer 작업에서 settings.gradle.kts / ci.yml / nightly.yml 등록을 빠뜨려 PR review 단계에서 발견됐다. 본 작업에서는 미리 체크리스트로 점검:

- [x] `settings.gradle.kts` — `"examples:tenant-aggregator"` 추가
- [x] `ci.yml` — `changes.outputs` + `paths-filter` + 신규 `test-examples-tenant-aggregator` job + `coverage-report.needs` + `ci-status.needs`
- [x] `nightly.yml` — 신규 `test-examples-tenant-aggregator` job + `coverage-report.needs` + `nightly-status.needs`
- [x] `src/test/resources/junit-platform.properties`
- [x] `src/test/resources/logback-test.xml`
- [x] `README.md` + `README.ko.md`

`grep -n "tenant-aggregator" .github/workflows/*.yml settings.gradle.kts` 로 마지막에 일괄 검증.

## 검증 결과

| Dialect | Pass | 소요 시간 |
|---------|------|----------|
| H2 | 10/10 | 5.4s |
| PostgreSQL | 10/10 | 7.9s |

테스트 커버:
1. 단일 인스턴스 — 모든 테넌트 polling 시작
2. 3 인스턴스 동시 — 테넌트당 정확히 1 인스턴스 (concurrent runners == 0 violations)
3. aggregate 예외 — 다음 사이클 계속 (poison 방지)
4. 리더 stop — 차순위 인계
5. start 후 cancelAndJoin — CancellationException 재throw 로 정상 종료
6. Options 검증 (blank nodeId / blank lockNamePrefix / 빈 tenants / blank 항목)
7. start 두 번 호출 — IllegalStateException
