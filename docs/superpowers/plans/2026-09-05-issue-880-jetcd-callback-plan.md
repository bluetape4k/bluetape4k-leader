# Issue #880 jetcd callback·watch·lease 회귀 검증 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 중앙 catalog의 jetcd `0.8.7` 전환을 원자적으로 적용하고, callback 내부 blocking 호출·ordered watch delivery·watch close/restart·publisher event·단일/group async 취소 정리 계약을 결정적 테스트와 최소 내부 수정으로 보장한다.

**Architecture:** raw jetcd watch 테스트는 `WatchOption.withCreateNotify(true)`의 created response를 준비 완료 barrier로 사용하고 blocking callback, ordered delivery, close/restart를 독립 시나리오로 분리한다. publisher 테스트는 `CoroutineStart.UNDISPATCHED`와 latch/future barrier로 collector 및 경합 시작을 고정한다. Etcd 단일/group async adapter는 기존 `LeaderFutureBridge`와 `WAITING`/`STARTED`/`CLEANUP` 원자 lifecycle 패턴을 재사용해 반환 future 취소를 실제 action과 lease cleanup에 전달한다. public API, key layout, client ownership, lease 정책은 바꾸지 않는다.

**Tech Stack:** Kotlin/JVM, jetcd `0.8.7`, JUnit 5, kotlinx-coroutines, bluetape assertions, Testcontainers etcd, Gradle dependency locking/version catalog, detekt, binary API checker, GitHub Actions.

---

## 실행 상태 체크박스

- [x] Task 1 — 기준 graph와 jetcd callback RED 고정
- [x] Task 2 — catalog pin 원자 전환과 callback GREEN
- [x] Task 3 — publisher watch 준비·경합 테스트 결정성 개선
- [x] Task 4 — Etcd 단일/group async lifecycle RED
- [x] Task 5 — 취소 전파·exactly-once cleanup GREEN
- [x] Task 6 — module·전체 저장소·ABI 검증
- [ ] Task 7 — 7-Tier 리뷰·lesson·PR·exact-head CI

## 0. 고정 전제와 실행 경계

- 기준은 `develop`의 `f9c084c241b5ac87a4b644f64ef47da55d71fbcb`이며 작업 branch/worktree는 `test/issue-880-jetcd-callback` / `.worktrees/test/issue-880-jetcd-callback`다.
- 승인된 설계는 `docs/superpowers/specs/2026-09-05-issue-880-jetcd-callback-design.md`이며 Issue [#880](https://github.com/bluetape4k/bluetape4k-leader/issues/880)과 jetcd [PR #1559](https://github.com/etcd-io/jetcd/pull/1559)의 callback 실행 변경을 근거로 한다.
- 중앙 catalog immutable ref `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`를 `settings.gradle.kts`와 `.github/workflows/ci.yml`에 함께 적용한다. 둘의 ref가 다른 중간 상태는 commit하지 않는다.
- raw watch 테스트는 callback executor의 구현 세부사항이나 thread name을 계약으로 고정하지 않는다. 검증 대상은 callback 내부 blocking KV 호출의 완료, 동일 watcher의 응답 순서, close 이후 전달 중지, 새 watcher 재개다.
- `EtcdLeaderElector`와 `EtcdLeaderGroupElector`의 public signature, etcd key layout, TTL/minLease/waitTime 기본값, 호출자 소유 `Client` lifecycle은 유지한다.
- async 수정은 기존 저장소의 `LeaderFutureBridge`와 원자 lifecycle 패턴을 재사용한다. 새 public abstraction이나 의존성을 추가하지 않는다.
- 고정 `Thread.sleep`/`delay`는 테스트 동기화 수단으로 추가하지 않는다. bounded timeout은 실패 진단 상한으로만 사용한다.
- README/KDoc/manual 변경은 public 계약이 달라지지 않으므로 `N/A`다. 구현 중 public 동작이 달라지는 사실이 발견되면 즉시 설계 게이트로 되돌아간다.
- push와 PR 생성은 이 계획에 포함되지만 merge, tag, release, publish, branch/worktree 삭제는 별도 권한 경계다. PR exact-head가 merge-ready여도 merge 전 fresh 승인을 받는다.

## 1. 파일 소유와 변경 지도

| 경로 | 작업 | 책임 |
|---|---|---|
| `settings.gradle.kts` | 수정 | 기본 중앙 catalog ref를 `9698c9d...`로 전환 |
| `.github/workflows/ci.yml` | 수정 | CI catalog ref를 기본 ref와 동일하게 전환 |
| `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/internal/JetcdWatchCallbackIntegrationTest.kt` | 신규 | blocking callback, ordered delivery, close/restart raw jetcd 검증 |
| `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElectionEventPublisherIntegrationTest.kt` | 수정 | collector 준비와 contender 경합에서 고정 delay 제거, close 계약 강화 |
| `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdAsyncLifecycleTest.kt` | 신규 | 단일/group action 취소, cleanup, 재획득, executor 거부 회귀 |
| `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdAsyncLeaderElectorIntegrationTest.kt` | 신규 | 실제 etcd에서 단일/group async 취소와 재획득 검증 |
| `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/contract/EtcdVirtualThreadLeaderElectorContractTest.kt` | 수정 | virtual-thread 취소 뒤 실제 etcd 재획득 검증 |
| `leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElector.kt` | 수정 | 단일 async cancellation relay와 원자 cleanup |
| `leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderGroupElector.kt` | 수정 | group async cancellation relay와 원자 cleanup |
| `docs/review/2026-09-05-issue-880-jetcd-callback-review.md` | 신규 | 7-Tier exact-diff 검토와 P0/P1 수렴 증거 |
| `docs/lessons/2026-09-05-issue-880-jetcd-callback.md` | 신규 | jetcd watch readiness와 async lease cleanup 재사용 교훈 |
| `.flow-inputs/checklist.md` | 로컬 갱신 | Type A gate 명령·count·SHA 증거, commit 제외 |

모든 production/test 파일은 inline main lane이 소유한다. 독립 검토 lane은 read-only이며, lane이 실패하거나 결과를 내지 못하면 사용자 지침대로 즉시 inline 검토로 대체한다.

## 2. Acceptance traceability

| Issue/설계 acceptance | 구현 작업 | 완료 증거 |
|---|---|---|
| `jetcd-core:0.8.7`과 중앙 gRPC/Netty/Vert.x graph | Task 1, 2 | 전·후 `dependencyInsight`, pin equality |
| callback 내부 blocking KV 호출이 deadlock 없이 완료 | Task 1, 2 | old ref RED, new ref GREEN인 targeted integration test |
| 느린 첫 callback 뒤 PUT/DELETE 순서 보존 | Task 1, 2 | ordered callback list와 bounded completion |
| watcher close 뒤 전달 중지, 새 watcher 재개 | Task 1, 2 | closed listener count 고정 + restarted listener event |
| publisher의 elected/revoked 의미와 queued contender 억제 | Task 3 | sleep 없는 publisher integration suite |
| sync/suspend/virtual-thread 및 단일/group lease 회귀 없음 | Task 5, 6 | 기존 contract/integration tests와 module full suite |
| 반환 future 취소가 실제 action으로 전파되고 lease 정리 | Task 4, 5 | single/group action `isCancelled`, unlock/revoke, 동일 lock/slot 재획득 |
| executor 거부 시 원래 예외 보존과 lease 정리 | Task 4, 5 | `RejectedExecutionException`, unlock/revoke, 재획득 |
| 첫 실행 안정성과 광역 catalog 호환성 | Task 6 | retry 없는 module test, full build, detekt, ABI |
| P0/P1 0 및 exact-head CI | Task 7 | 7-Tier artifact, PR checks/threads/mergeability read-back |

## 3. Task 1 — 기준 graph와 jetcd callback RED 고정

**Files:** 신규 `JetcdWatchCallbackIntegrationTest.kt`, `.flow-inputs/checklist.md`.

1. `[x]` 현재 ref에서 기준 graph를 보존한다. 각 명령의 selected version과 selection reason을 checklist에 기록한다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.etcd:jetcd-core --configuration testRuntimeClasspath --no-daemon --console=plain
   ./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.grpc --configuration testRuntimeClasspath --no-daemon --console=plain
   ./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.netty --configuration testRuntimeClasspath --no-daemon --console=plain
   ./gradlew :bluetape4k-leader-etcd:dependencyInsight --dependency io.vertx --configuration testRuntimeClasspath --no-daemon --console=plain
   ```

   old ref에서 `io.etcd:jetcd-core:0.8.6`이 선택되어야 한다. 출력은 secret이 없음을 확인한 뒤 증거 경로에 보존한다.

2. `[x]` raw jetcd fixture를 먼저 추가한다. watcher는 `WatchOption.newBuilder().withCreateNotify(true).build()`를 사용하고 첫 empty `WatchResponse`의 `isCreated`를 `CompletableFuture<Unit>` 또는 `CountDownLatch` readiness로 변환한다. readiness가 10초 안에 오지 않으면 테스트를 실패시키며 임의 delay로 대체하지 않는다.

3. `[x]` blocking callback 테스트를 추가한다. created barrier 이후 PUT을 발생시키고 event callback 안에서 같은 `Client.kvClient.get(key).get(10, TimeUnit.SECONDS)`를 호출해 방금 저장한 값을 읽는다. callback 결과 future가 10초 안에 완료되고 예외가 없음을 검증한다.

4. `[x]` ordered delivery 테스트를 별도로 추가한다. 첫 PUT callback은 latch에서 대기시키되 callback 진입을 main test에 알린다. 첫 callback이 대기 중인 동안 두 번째 PUT과 DELETE를 발행하고 latch를 해제한다. 관측 목록이 revision 증가 순서의 `PUT(v1)`, `PUT(v2)`, `DELETE`와 일치해야 한다. 병렬 callback 개수나 thread 이름은 assertion하지 않는다.

5. `[x]` close/restart 테스트를 별도로 추가한다. created barrier를 지난 첫 watcher를 close한 뒤 event count snapshot을 잡고 PUT을 수행한다. 첫 listener count가 변하지 않음을 bounded negative window로 확인하고, 같은 client에서 만든 두 번째 watcher의 created barrier와 다음 PUT 수신을 확인한다. negative window는 `poll`/future timeout으로 표현하고 고정 sleep을 사용하지 않는다.

6. `[x]` RED 검증은 old catalog ref에서 blocking test만 실행한다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:test --tests '*JetcdWatchCallbackIntegrationTest.callback can perform blocking kv get*' --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks --console=plain
   ```

   jetcd `0.8.6`에서 callback 내부 blocking call이 bounded timeout으로 실패해야 Issue #880의 dependency-driven RED가 성립한다. compile 오류, Docker 오류, fixture readiness 실패는 유효한 RED가 아니며 먼저 fixture를 수정한다. 예상과 달리 old ref가 GREEN이면 upstream diff와 test sensitivity를 재검토하고 dependency upgrade를 정당화하는 별도 행동 차이가 확인되기 전까지 Task 2로 진행하지 않는다.

7. `[x]` watcher/client는 `use`/`try-finally`로 닫고 key는 test마다 고유 prefix를 사용한다. timeout 시 outstanding future와 watcher를 정리해 다음 테스트로 누출하지 않는다.

## 4. Task 2 — catalog pin 원자 전환과 callback GREEN

**Files:** `settings.gradle.kts`, `.github/workflows/ci.yml`, `JetcdWatchCallbackIntegrationTest.kt`.

1. `[x]` `settings.gradle.kts`와 `.github/workflows/ci.yml`의 기존 `850959d0ea5f76ac7e2c442400f47653d5f95eed`를 `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`로 한 patch에서 바꾼다.

2. `[x]` 두 ref가 정확히 하나이고 동일한지 검증한다.

   ```bash
   rg -n '850959d0ea5f76ac7e2c442400f47653d5f95eed|9698c9d66bea6fcba373143ee8fa5bfbd9812d4b' settings.gradle.kts .github/workflows/ci.yml
   ```

   old ref는 0건, new ref는 두 파일에서 각 1건이어야 한다.

3. `[x]` Task 1의 네 `dependencyInsight`를 다시 실행한다. `jetcd-core:0.8.7`, gRPC/Netty/Vert.x selected version과 `selected by rule`/catalog constraint 근거를 review artifact에 기록한다. 중앙 catalog의 광역 변경 때문에 예상하지 못한 downgrade/conflict가 있으면 구현을 중지하고 두 pin을 함께 rollback한다.

4. `[x]` 세 callback 테스트를 한 invocation으로 실행한다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:test --tests '*JetcdWatchCallbackIntegrationTest*' --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks --console=plain
   ```

   첫 실행에서 모두 GREEN이어야 한다. retry로만 통과하면 PASS가 아니라 stability finding으로 기록하고 원인을 고친다.

5. `[x]` commit은 catalog 두 pin, raw callback tests, RED/GREEN 증거를 하나의 의도 단위로 묶고 Korean Lore 형식을 사용한다. rollback은 해당 commit revert로 두 pin과 tests를 함께 되돌릴 수 있어야 한다.

## 5. Task 3 — publisher watch 준비·경합 테스트 결정성 개선

**Files:** `EtcdLeaderElectionEventPublisherIntegrationTest.kt`.

1. `[x]` collector가 producer보다 먼저 구독하도록 모든 `async { publisher.events... }`를 `async(start = CoroutineStart.UNDISPATCHED) { ... }`로 바꾸고 collector 준비용 `delay(250)`를 삭제한다.

2. `[x]` queued contender 테스트의 `delay(500)`를 제거한다. holder 진입 latch 이후 contender가 실제 acquisition을 시작했음을 executor-side latch로 알리고, publisher가 holder `Elected`를 전달한 것을 positive barrier로 확인한 뒤 holder를 해제한다. assertion은 최종 event sequence가 `elected`, `revoked`, `elected`, `revoked`이고 queued 상태에서 추가 `Elected`가 발생하지 않았다는 기존 의미를 유지한다.

3. `[x]` close 테스트를 확장한다. publisher를 close한 뒤 동일 prefix에 실제 ownership 변화를 만들고 closed publisher의 `first()` collector가 완료되지 않음을 `future.get(500, TimeUnit.MILLISECONDS)`의 `TimeoutException`으로 확인한 뒤 collector를 취소한다. 이어 같은 caller-owned client로 새 publisher/elector가 정상 event를 전달함을 확인한다. client usability만 확인하는 기존 assertion도 유지한다.

4. `[x]` RED/GREEN 검증은 수정할 테스트 class만 매번 실행한다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:test --tests '*EtcdLeaderElectionEventPublisherIntegrationTest*' --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks --console=plain
   ```

   테스트 source의 `delay(`가 0건이어야 한다. negative assertion timeout은 완료 조건이 아니라 불발 검증 상한임을 주석 없이 코드 구조로 드러낸다.

## 6. Task 4 — Etcd 단일/group async lifecycle RED

**Files:** 신규 `EtcdAsyncLifecycleTest.kt`.

1. `[x]` `EtcdLockClient` fake는 granted lease, pending/complete lock future, ownership key, unlock/revoke call count를 thread-safe하게 기록한다. active lease/ownership을 상태로 보유하고 unlock/revoke 전의 두 번째 acquisition은 완료시키지 않아 후속 재획득 assertion이 실제 cleanup을 검증하도록 한다. action은 직접 제어하는 `CompletableFuture<T>`를 반환한다. 새 예외 assertion은 반드시 `io.bluetape4k.assertions.assertFailsWith`를 사용한다.

2. `[x]` 단일 elector RED를 추가한다. action 진입을 barrier로 확인하고 반환 future를 `cancel(true)`한다. 실제 action future가 `isCancelled == true`, unlock/revoke가 각각 정확히 한 번, 후속 동일 lock 실행이 성공함을 확인한다.

3. `[x]` group elector RED를 `maxLeaders=1`로 추가한다. 반환 future 취소 후 실제 action 취소, 동일 slot cleanup, 후속 같은 group lock 재획득을 확인한다.

4. `[x]` acquisition 대기 중 취소 시 action이 호출되지 않고, 늦게 완료된 ownership key가 unlock/revoke되는 single/group case를 추가한다. returned future의 cancel과 lock future 완료 경합을 barrier로 고정한다.

5. `[x]` lease 획득과 action 제출 사이 executor 거부를 재현하는 scripted executor를 추가한다. 첫 `execute`는 acquisition task를 실행하고 두 번째 `execute`는 `RejectedExecutionException("rejected-after-acquire")`을 던져야 한다. 결과 cause가 원래 예외이고 unlock/revoke가 정확히 한 번이며 후속 재획득이 성공해야 한다. 별도 always-reject executor는 acquisition 전 거부가 backend 호출 0건임을 확인할 때만 사용한다.

6. `[x]` 현재 구현에서 targeted class를 실행해 유효한 RED를 얻는다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:test --tests '*EtcdAsyncLifecycleTest*' --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks --console=plain
   ```

   예상 RED는 실제 action future 미취소 또는 lease cleanup 지연/누락이다. fake compile 오류나 timeout-only failure는 유효한 RED로 인정하지 않는다.

## 7. Task 5 — 취소 전파·exactly-once cleanup GREEN

**Files:** `EtcdLeaderElector.kt`, `EtcdLeaderGroupElector.kt`, `EtcdAsyncLifecycleTest.kt`, 신규 `EtcdAsyncLeaderElectorIntegrationTest.kt`, `contract/EtcdVirtualThreadLeaderElectorContractTest.kt`.

1. `[x]` 두 elector에 private `AsyncLifecycle { WAITING, STARTED, CLEANUP }`을 두고 `AtomicReference` CAS로 action 시작과 cleanup 소유권을 직렬화한다. 취소와 action 시작 중 하나만 `WAITING`을 선점하며 cleanup은 lease handle의 `markReleased()`와 함께 exactly once다.

2. `[x]` acquisition은 caller executor에서 수행하되 source/returned future를 `LeaderFutureBridge`로 연결한다. 반환 future 취소는 acquisition source, 실제 action future, watchdog/cleanup에 전파한다. action supplier가 throw하면 failed future와 동일한 cleanup path로 수렴한다.

3. `[x]` action은 lease 획득 뒤에만 호출한다. `LeaderFutureBridge.cancellationRelay()`를 action future에 설치하고 result mapping에서 `CompletionException` wrapper를 불필요하게 노출하지 않는다. 정상 contention은 기존처럼 `null`을 반환한다.

4. `[x]` `LeaderLockHandle`의 single/group identity, group `slotId`/`auditLeaderId`, AOP scope capture, autoExtend 설정, `minLeaseTime`, unlock→revoke 순서와 caller-owned client 계약을 보존한다. group은 기존과 같이 watchdog disabled 의미를 유지한다.

5. `[x]` executor가 initial acquisition 또는 post-acquire action 실행을 거부할 때 원래 `RejectedExecutionException`을 result에 보존한다. lease가 이미 획득된 경우 cleanup을 먼저 설치한 뒤 제출하며, 획득 전 거부에는 backend 호출이 없어야 한다.

6. `[x]` Task 4 targeted class를 GREEN으로 만들고 다음 기존 회귀 tests도 실행한다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:test \
     --tests '*EtcdAsyncLifecycleTest*' \
     --tests '*EtcdLeaderCleanupTimeoutTest*' \
     --tests '*EtcdLeaderElectorIntegrationTest*' \
     --tests '*EtcdLeaderGroupElectorIntegrationTest*' \
     --tests '*EtcdSuspendLeaderElectorIntegrationTest*' \
     --tests '*EtcdSuspendLeaderGroupElectorIntegrationTest*' \
     --tests '*EtcdVirtualThreadLeaderElectorContractTest*' \
     --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks --console=plain
   ```

7. `[x]` 실제 etcd integration test에서 단일/group `runAsyncIfLeader`의 action future를 취소하고 returned future의 취소 상태, 같은 lock/slot 재획득을 확인한다. virtual-thread contract에는 action 진입 후 returned `VirtualFuture` 취소와 같은 lock 재획득을 추가한다. 기존 sync sequential reacquire, sync contention, suspend single/group cancellation tests와 함께 execution-model matrix를 완성한다.

8. `[x]` async action supplier가 `extendActiveLock()`을 호출하는 기존 scope 의미와 auto-extend cleanup을 fake 또는 integration test로 고정한다. 구현이 기존 scope 의미를 보존할 수 없거나 의미가 불명확하면 무단으로 축소하지 않고 설계 게이트로 되돌아간다.

9. `[x]` duplicated single/group lifecycle code는 먼저 기존 두 클래스의 대칭성을 유지한다. 검증된 반복이 private internal helper로 명확히 줄어들지 않는 한 새 abstraction을 만들지 않는다.

## 8. Task 6 — module·전체 저장소·ABI 검증

**Files:** 모든 변경 파일, Gradle/JUnit 결과, `.flow-inputs/checklist.md`.

1. `[x]` source hygiene와 결정성 정적 검사를 한다.

   ```bash
   git diff --check
   rg -n 'delay\(|Thread\.sleep\(' leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/internal/JetcdWatchCallbackIntegrationTest.kt leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElectionEventPublisherIntegrationTest.kt leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdAsyncLifecycleTest.kt leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdAsyncLeaderElectorIntegrationTest.kt
   rg -n 'assertThrows|kotlin\.test\.assertFailsWith|shouldThrow' leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd
   ```

   첫 명령은 exit 0, 두 번째와 세 번째는 0건이어야 한다.

2. `[x]` `leader-etcd` 전체 테스트를 retry 없이 clean first-run으로 실행한다. CI의 5회 retry wrapper를 로컬 성공 증거로 사용하지 않는다.

   ```bash
   ./gradlew :bluetape4k-leader-etcd:cleanTest :bluetape4k-leader-etcd:test --no-daemon --no-configuration-cache --no-build-cache --rerun-tasks --console=plain
   ```

   JUnit XML의 tests/failures/errors/skipped 합계와 elapsed time을 checklist/review에 기록한다. unexplained retry나 skipped integration test는 PASS가 아니다.

3. `[x]` central catalog 광역 영향과 public ABI를 검증한다.

   ```bash
   ./gradlew detekt --no-daemon --console=plain
   ./gradlew checkBinaryCompatibility --no-daemon --console=plain
   ./gradlew build --no-daemon --console=plain
   ```

   모두 exit 0이어야 한다. 환경상 전체 build를 실행할 수 없다면 정확한 실패와 대체 범위를 `PENDING`으로 남기고 PR merge-ready를 주장하지 않는다.

4. `[x]` sync/async/suspend/virtual-thread와 single/group 결과를 verification matrix에 명시한다. 테스트가 존재하지 않는 조합은 임의 PASS가 아니라 `N/A` 또는 `PENDING`으로 분류하고 근거를 쓴다.

5. `[x]` 두 catalog pin equality, `jetcd 0.8.7`, gRPC/Netty/Vert.x graph, 변경된 public API 0건, README/KDoc `N/A`를 최종 증거에 다시 기록한다.

## 9. Task 7 — 7-Tier 리뷰·lesson·PR·exact-head CI

**Files:** 신규 review/lesson 문서, 전체 diff, GitHub PR.

1. `[x]` `$bluetape-kotlin-patterns`의 7-Tier 순서로 exact diff를 검토한다: Kotlin correctness, concurrency/cancellation, API/ABI, backend/watch semantics, tests/flakiness, dependency/security/ops, CI/delivery. 발견은 파일/line·재현·severity·처리 상태를 `docs/review/2026-09-05-issue-880-jetcd-callback-review.md`에 기록한다.

2. `[x]` performance/stability scan을 별도로 수행한다. callback backpressure, bounded waits, thread/executor leak, watcher close race, CAS ownership, cleanup count, full-suite runtime을 확인한다. P0/P1은 PR 전 모두 고치고 다시 검증한다.

3. `[x]` 독립 review lane을 한 번 실행한다. lane이 90초 안에 유효 결과를 내지 못하거나 unavailable이면 중단하고 사용자 standing rule에 따라 같은 checklist를 inline으로 완결한다. human reviewer는 solo-maintainer lane에서만 `N/A`; 기술 검토는 생략하지 않는다.

4. `[x]` `docs/lessons/2026-09-05-issue-880-jetcd-callback.md`에 created notification readiness, dependency-sensitive RED, blocking callback/backpressure, action cancellation relay, exactly-once cleanup, atomic catalog rollback을 재사용 가능한 규칙으로 기록한다.

5. `[x]` 변경을 작은 Lore commit으로 정리한다. 각 commit은 한국어 intent line, `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` 중 필요한 trailer를 포함한다. commit 전마다 `git diff --check`와 관련 targeted test를 다시 읽는다.

6. `[ ]` PR 생성 직전 live `gh`로 Issue #880, milestone `1.1.0`, labels, assignee, 기존 open PR/duplicate를 다시 확인한다. head `test/issue-880-jetcd-callback`, base `develop`을 명시하고 한국어 PR body 마지막을 `## DoD Status`로 끝낸다. PR 생성은 승인됐지만 merge는 승인되지 않았다.

7. `[ ]` exact-head SHA를 pin하고 terminal CI를 확인한다. required checks, test count, skipped/failed jobs, review/thread read-back, mergeability를 기록한다. path-filtered skip, old SHA green, retry-only green은 exact-head PASS가 아니다.

8. `[ ]` 최종 상태는 다음이 모두 충족되면 `PENDING (merge approval)`이다: 로컬/CI exact-head 성공, P0=0/P1=0, open review thread 0, mergeable, 변경된 public API 0, rollback 경계 명확. fresh merge 승인 전에는 merge·branch 삭제·worktree 삭제를 수행하지 않는다.

## 10. Rollback과 중단 조건

- catalog 회귀가 원인이면 `settings.gradle.kts`와 `.github/workflows/ci.yml`의 ref를 같은 patch/commit에서 이전 `850959d0ea5f76ac7e2c442400f47653d5f95eed`로 되돌린다. 한 파일만 rollback한 상태는 허용하지 않는다.
- callback test가 old ref에서도 GREEN이면 test sensitivity를 먼저 수정한다. jetcd `0.8.7`의 행동 차이를 증명하지 못하면 dependency upgrade를 진행하지 않는다.
- async fix가 public API/ABI, key layout, lease policy를 바꿔야 한다면 현재 설계를 넘어선 것이므로 구현을 중지하고 Type A 설계 승인을 새로 받는다.
- Testcontainers/Docker가 unavailable이면 fake test만으로 backend callback PASS를 선언하지 않는다. 환경을 진단하고 integration 항목을 `PENDING`으로 둔다.
- central catalog 때문에 비-etcd module build가 실패하면 실패 module과 dependency edge를 기록하고 local force/override로 감추지 않는다.

## 11. 계획 검토 결과

독립 test-engineering lane은 90초 bounded wait 안에 결과를 반환하지 않아 중단했다. 사용자 standing rule에 따라 같은 범위를 inline으로 검토했고, 설계 검토에서 사용한 여섯 관점과 test-engineering·spec traceability를 함께 적용했다.

| 우선순위 | 관점 | 발견 | 계획 반영 |
|---|---|---|---|
| P1 | Stability/Test | 단순 call-count fake에서는 cleanup 없이도 후속 acquisition이 성공해 재획득 assertion이 무효가 될 수 있음 | fake가 active ownership을 유지하고 unlock/revoke 전 재획득을 막도록 Task 4를 보강 |
| P1 | Test/Operator | always-reject executor는 lease 획득 뒤 제출 거부를 재현하지 못함 | 첫 acquisition task는 받고 두 번째 제출만 거부하는 scripted executor를 명시 |
| P1 | Developer/API | fake만으로 direct async와 virtual-thread 실제 etcd 재획득을 증명할 수 없음 | `EtcdAsyncLeaderElectorIntegrationTest`와 virtual cancellation contract를 파일 지도·Task 5에 추가 |
| P1 | Developer/API | 다른 backend의 async pattern을 그대로 복사하면 Etcd의 기존 AOP scope/auto-extend 의미가 축소될 수 있음 | `extendActiveLock()` supplier 의미와 watchdog cleanup을 먼저 테스트하고 불명확하면 설계로 복귀 |
| P2 | Stability | close 이후 불발 검증이 추상적인 polling이면 구현마다 다른 wait가 생김 | `first()` future의 bounded `TimeoutException`과 명시적 collector cancel로 고정 |
| P2 | Performance | callback block을 순서 테스트와 섞으면 원인과 suite 시간이 불명확함 | blocking, ordered delivery, close/restart를 독립 test로 유지하고 한 container invocation에서 실행 |
| P2 | Security | jetcd channel 전환과 중앙 catalog 광역 변경이 TLS/auth 또는 transitive downgrade를 숨길 수 있음 | client 설정 불변, 네 graph의 before/after selection reason, full build를 요구 |
| P2 | Operator/Ops | local/CI pin 불일치와 retry-only green이 rollout 결함을 숨길 수 있음 | 두 pin 원자 변경·rollback, retry 없는 local first-run, exact-head CI read-back을 요구 |
| P3 | User/caller | public 계약 불변인데 README/manual을 바꾸면 검증 범위를 사용자 기능처럼 보이게 함 | README/KDoc/manual은 근거를 적어 `N/A` 유지 |

통합 뒤 남은 계획 finding은 `P0=0`, `P1=0`이다. P2는 Task 1–7의 실행 검증 항목으로 추적한다.

### 계획 검토 체크리스트

- [x] Performance: callback backpressure와 bounded wait가 suite/runtime·thread leak을 만들지 않는다.
- [x] Stability: created barrier, close/restart, action-start/cancel CAS, late acquisition cleanup이 race를 결정적으로 재현한다.
- [x] Security: Netty/TLS/auth client 설정을 바꾸지 않으며 dependency graph의 예상치 못한 downgrade/conflict가 없다.
- [x] Operator/Ops: local/CI pin이 동일하고 rollback이 원자적이며 첫 실패 로그가 보존된다.
- [x] Developer/API: public descriptor와 contention `null`, exception cause, client ownership 계약이 유지된다.
- [x] User/caller: README/KDoc/manual 변경 `N/A`의 근거가 public 계약 불변과 일치한다.
- [x] Test engineering: RED가 의도한 결함으로 실패하고 GREEN이 retry/sleep/환경 우연에 의존하지 않는다.
- [x] Spec traceability: Issue acceptance 각각에 task와 fresh command evidence가 있다.
- [x] Delivery: PR exact-head와 CI, review threads, mergeability를 merge 승인 직전에 다시 읽는다.

## 12. 계획 DoD

- [x] 승인된 설계의 acceptance 10개가 Task 1–7과 검증 명령에 연결됐다.
- [x] 변경 파일, ownership, RED/GREEN, rollback, public API/N/A 경계가 명시됐다.
- [x] 6개 관점과 test-engineering 검토에서 P0=0, P1=0이다.
- [x] 계획 승인 전 production/test 구현을 시작하지 않았다.
- [ ] 계획 commit SHA와 workflow Type A receipt가 기록됐다.
