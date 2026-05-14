# Lessons Learned — Issue #79 LockExtender / LockAssert (2026-05-11)

**관련 PR**: #178 → #179 → #182 → #190 → #192 → #193 → #195 → #196 → #197 (9 PRs)
**영향 모듈**: leader-core, leader-spring-boot, leader-redis-lettuce, leader-redis-redisson, leader-mongodb, leader-exposed-jdbc, leader-exposed-r2dbc, leader-hazelcast, leader-zookeeper, leader-ktor

## L1: AC-15 single-instance ExtendDelegate reference identity

### 문제
9개 backend 가 모두 `handle.extendDelegate` 와 `LeaderLeaseAutoExtender.start(...).delegate` 를 별도 인스턴스로 생성하면 watchdog 갱신과 user explicit extend 가 서로 다른 `lastExtendDeadline` 을 가져 R2 watchdog skip semantics 깨짐.

### 교훈
elector 안에서 **single `val delegate = XxxLockExtendDelegate(lock)` 인스턴스를 생성하여** `LeaderLockHandle.real(extendDelegate = delegate)` 와 `LeaderLeaseAutoExtender.start(..., delegate, classifier)` 양쪽에 **동일 reference 로 전달**한다. unit test 로 verify 가능: `LockExtender.extendActiveLockDetailedSuspend(d)` 가 `Extended` 반환하면 reference identity 확인됨 (NoopExtendDelegate 가 아닌 real backend 와 연결).

---

## L2: R6 expired-doc/row revival 차단 — 모든 token-based backend SQL/filter 에 `expireAt > now` 추가

### 문제
초기 spec 의 `extend` 가 `{lock_name, token}` 만 검사 → token 일치 + lease 만료 row 가 살아있는 race window 에서 다른 인스턴스가 takeover 한 row 를 stale token 이 revival 시킬 수 있음 (split-brain).

### 교훈
모든 token-based backend (Lettuce / Mongo / Exposed JDBC / Exposed R2DBC) `extendDetailed` filter 에 `expireAt > now()` 또는 동등한 조건 추가 필수. Hazelcast/ZK 는 backend auto-evict / session-bound 라서 별도 필요 없음. Redisson 은 thread-id owner-guard 로 동등 효과.

---

## L3: Suspend vs Sync — `runBlocking` bridge / `withContext(Dispatchers.IO)` 선택

### 문제
backend 마다 driver 가 blocking (JDBC, Hazelcast Java API, Curator) / reactive native (R2DBC, Mongo Kotlin Coroutine, Lettuce Reactive) 다르므로 ExtendDelegate 의 `extend()` / `extendSuspend()` 라우팅 통일이 모호.

### 교훈
- **Sync delegate** (wraps sync lock): `extend()` direct, `extendSuspend()` → `withContext(Dispatchers.IO) { ensureActive(); ... }`
- **Suspend delegate** (wraps suspend lock): `extend()` → `runBlocking { lock.extendDetailed(d) }` bridge, `extendSuspend()` → direct call
- Driver 가 reactive native 인 경우 suspend lock 자체에 `withContext(IO)` 래핑 불필요 (advisor 핵심 통찰).

---

## L4: ZooKeeper passthrough semantics — `Extended(Instant.MAX) + R16 autoExtend=false enforce`

### 문제
ZK 는 TTL 이 없고 session-bound 라 `extend(d)` 가 의미 없음. spec §6 row 12 가 명시: passthrough — `if (mutex.isAcquiredInThisProcess()) Extended(Instant.MAX) else NotHeld`.

### 교훈
- `Extended(observedExpireAt = Instant.MAX)` 는 "lease 연장" 이 아닌 "session-held liveness" 신호.
- **R16 mandatory**: 모든 ZK elector `LeaderLeaseAutoExtender.start(enabled = false, ...)` 강제. user-requested `autoExtend=true` 는 WARN log 후 무시.
- Curator `Lease` 의 liveness API 부재 → `AtomicBoolean released` + elector 가 `markReleased()` 호출 race-safe pattern.

---

## L5: Hazelcast EntryProcessor — testcontainers vanilla 서버 호환성 함정

### 문제
spec §6 row 11 specified `IMap.executeOnKey(ExtendEntryProcessor)`. 구현 후 Testcontainers Hazelcast 서버에서 `HazelcastSerializationException(ClassNotFoundException)` 발생. 사용자 정의 `EntryProcessor` 클래스가 server JVM classpath 에 없어서.

### 교훈
- Hazelcast client-server 모드에서 사용자 정의 `EntryProcessor` 사용은 **`UserCodeDeployment` 설정 필수** (testcontainers 기본 이미지로는 불가).
- 대신 built-in atomic primitive 조합 사용: `IMap.replace(K, ourToken, ourToken)` (CAS) + `IMap.setTtl(K, leaseMs, MS)`. Token-guard 의미 보존 + race window 는 KDoc 문서화.
- 임베디드 Hazelcast 와 client-server Hazelcast 의 deserialization 차이를 사전 검증.

---

## L6: capture 패턴 — sync (`withPushedSync`) / suspend (`createLockHandleElement`) / group (`setCapture` 추가)

### 문제
AOP aspect 가 `@LeaderElection` 어노테이션에서 `LockExtender` 를 호출할 수 있도록 elector 안에서 `LockHandleElement` 를 propagate 해야 함. sync 와 suspend, single 과 group 마다 다른 mechanism 필요.

### 교훈
- **Sync single**: `AopScopeAccess.withPushedSync(handle) { action() }` — ThreadLocal push/pop
- **Suspend single**: `withContext(coroutineContext + AopScopeAccess.createLockHandleElement(handle)) { action() }` — CoroutineContext.Element 주입
- **Sync group**: `withPushedSync + setCapture(handle) + clearCapture()` in finally — group elector 의 capture invariant 위해 추가 ThreadLocal
- **Suspend group**: `createLockHandleElement` only. Do not use ThreadLocal capture in suspend electors; dispatcher hops can split set/clear across carrier threads.
- 4개 패턴 모두 MongoDB elector 가 reference template — 9개 backend 통일 적용.

---

## L7: bluetape4k-junit5 concurrency tester 활용 의무

### 문제
multi-thread / coroutine race / structured concurrency 검증 시 직접 `Thread` / `Executors.newFixedThreadPool` / `coroutineScope { launch }` 작성하면 race condition false-positive/negative, resource leak, 비결정적 테스트 위험.

### 교훈
다음 시나리오에서 **직접 thread/coroutine 생성 금지**:
- race-free 단조성 검증
- contention stress test
- watchdog × user explicit extend 동시 호출
- 분산 락 concurrent acquire/release
- structured concurrency 검증

대신 **`bluetape4k-junit5`** 의 다음 도구 사용:
- **`MultithreadingTester`** — Platform thread 기반 concurrent stress
- **`StructuredTaskScopeTester`** — Java 21 `StructuredTaskScope` 기반 구조적 동시성
- **`SuspendedJobTester`** — Kotlin coroutine 기반 suspend race (`runTest` + virtual time 호환)

→ `bluetape4k-design` Step 4-T checklist 에 명시됨.

---

## L8: bluetape4k-patterns skill 명시적 invoke 의무

### 문제
sub-agent dispatch 시 prompt directive 만으로는 충분하지 않음. KLogging companion, `requireNotBlank`, no `!!`, `runCatching` 경계 등 패턴 적용 누락 발생.

### 교훈
`bluetape4k-design` Step 4 / Step 6-1 진입 전 `Skill("bluetape4k-patterns")` **명시 호출**. agent prompt 에 "Skill('bluetape4k-patterns') 먼저 invoke 후 작업" 명시. 코드 리뷰 agent 에도 동일하게 강제.

---

## L9: GitHub Actions runner queue saturation 대응 — background watch + 누적 wakeup

### 문제
PR 한 번에 9개 백엔드 CI 동시 실행 + Testcontainers 사용 → 러너 큐 saturation. CI 완료까지 30분-2시간 소요. polling 으로 토큰 소모.

### 교훈
- background watch shell script (`until ... done`) + `run_in_background=true` 사용 → task notification 으로 완료 알림 받음
- ScheduleWakeup 으로 30분-1시간 간격 점검. 5분 미만 sleep 금지 (cache 활용).
- 토큰 절약: 매 polling 시 status 전체 조회 대신 `pending count == 0` 까지 wait

---

## L10: 9-PR 분할 — Hybrid strategy (Core + backend-by-backend)

### 문제
초기에 단일 거대 PR 검토 → review burden 폭발, 머지 리스크 누적.

### 교훈
- PR 1: Core (interface + SPI + LockExtender / LockAssert / LeaderLockHandle + spring-boot AOP) → 모든 backend 공통 의존성 먼저
- PR 2-8: backend-by-backend (각 PR 이 PR 1 만 의존) — 병렬 진행 가능
- PR 9: cross-cutting smoke (ktor) → 마지막
- 한 PR 당 7-17 files, +500-900 LOC. Tier 4 review (opus) 각 PR 별 명확.

---

## L11: async path watchdog wiring 누락 — review checklist 패턴

### 문제
PR 5 (JDBC) 구현 시 sync `runIfLeader` 경로는 ExtendDelegate + watchdog 등록을 모두 처리했지만, `runAsyncIfLeader` 경로는 watchdog 등록 누락. `autoExtend = true` + long-running async action 시 backend lease 갱신 안 되어 다른 노드 takeover → split-brain 가능. Tier 4 review (opus) 가 1차에서 HIGH-1으로 캐치하여 머지 전 fix.

### 교훈
Sync / async / suspend 모든 entry path 가 동일한 ExtendDelegate + watchdog wiring 패턴을 가져야 함. **review checklist 에 "all entry paths watchdog parity" 항목 추가**:
- sync `runIfLeader`
- async `runAsyncIfLeader` (CompletableFuture)
- suspend `runIfLeader` (coroutine)
- VirtualThread `runIfLeader` (가상 스레드)

각 경로마다 (1) delegate 생성, (2) handle 에 동일 reference 전달, (3) watchdog start, (4) finally / whenComplete 에서 watchdog.close() 확인.

---

## L12: workflow YAML regex 편집 함정 — 인접 env 블록 중복 발생

### 문제
PR 199 에서 `koverVerify` step 4개 제거하려고 Python regex 로 일괄 삭제. 그러나 "Verify Kover threshold" step 의 `env:` 블록 일부 라인이 인접한 다음 step (혹은 잔존하는 env 블록) 과 합쳐져 **`TESTCONTAINERS_RYUK_DISABLED` / `DOCKER_HOST` 키가 중복 정의됨**. `gh workflow run` 시도 시 GitHub Actions 가 HTTP 422 `'TESTCONTAINERS_RYUK_DISABLED' is already defined` 반환 — workflow 자체가 invalid 로 인식.

### 교훈
- **YAML 구조 편집 시 regex 사용 금지** — 들여쓰기 + 인접 블록 경계 인식 못 함. 대신 `yq` (Yet another YAML processor) 사용:
  ```bash
  yq eval 'del(.jobs.*.steps[] | select(.name == "Verify Kover threshold"))' -i nightly.yml
  ```
- regex 로 편집했으면 **반드시 `gh workflow view` 또는 `gh workflow run` (dry) 로 syntax 검증**. push 전 lint 단계 필수.
- pre-push hook 에 `actionlint` 또는 `yamllint` 추가 검토.

---

## L13: kover rigid threshold → flexible coverage 가시화 (사용자 피드백)

### 문제
nightly CI 가 `koverVerify` 로 4개 모듈 (core/micrometer/spring-boot/zookeeper) 의 coverage threshold (60-80%) 를 hard gate 로 검증. Issue #79 PR 8 (ZK) 가 5개 internal 파일 추가하면서 79.71% < 80% → koverVerify FAILED. 사용자 피드백: rigid 한 hard gate 가 새 코드 추가 시마다 build break → 개발 흐름에 마찰.

### 교훈
- **coverage 는 가시화 지표 (Codecov / koverXmlReport) 로 유지**, hard gate 로 강제하지 않음. 목표는 "trend 모니터링 + 의식적 테스트 추가 활동" 이지 "build break" 아님.
- `kover { reports { verify { rule { bound { minValue } } } } }` 블록 제거 + nightly.yml `Verify Kover threshold` step 제거.
- `koverXmlReport` + Codecov 업로드는 유지 — coverage 변화 추적 가능.
- 도구는 entry 패턴이지 정책 자체가 아님 — 정책 (80% threshold) 은 코드 리뷰 + 팀 합의로 관리.
