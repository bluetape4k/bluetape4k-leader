# Issue #880 jetcd callback 및 async lifecycle 7-Tier 코드 리뷰

## DoD 판정

- 기준: `origin/develop` `f9c084c241b5ac87a4b644f64ef47da55d71fbcb`
- 구현 검토 head: `84b8ac3bcd3c1f11f891418273f3b661fd1b2c3a`
- 변경 branch/worktree: `test/issue-880-jetcd-callback` / `.worktrees/test/issue-880-jetcd-callback`
- 이슈: [#880](https://github.com/bluetape4k/bluetape4k-leader/issues/880), milestone `1.1.0`
- 리뷰 모델: `gpt-5.6-luna`, effort `max`
- 인라인 판정: **P0=0, P1=0, P2=1, P3=0**
- 전달 판정: **PENDING** — 로컬 검증은 완료됐고 PR exact-head CI가 남아 있다.

독립 review lane이 유효한 결과를 반환하지 못해 사용자 standing rule에 따라 동일한 7-Tier checklist를 인라인으로 완결했다. 이는 human review를 대체했다는 뜻이 아니다. solo-maintainer의 부재한 human-review subgate만 `N/A`이며, 기술 검증과 exact-head CI는 계속 필수다.

## 7-Tier 결과

| Tier | 판단 및 근거 | 상태 |
|---|---|---|
| 1. Kotlin correctness | `EtcdLeaderElector.kt:108-213`과 `EtcdLeaderGroupElector.kt:123-235`는 public signature를 유지하고, 실패 cause를 `CompletionException` 한 겹으로 정규화한다. action supplier의 동기 throw도 failed future로 변환한 뒤 lease를 정리한다. | PASS |
| 2. Concurrency/cancellation | 두 elector가 `WAITING`/`STARTED`/`CLEANUP` CAS로 action 시작과 cleanup ownership을 직렬화한다. 반환 future 취소는 acquisition source와 실제 action future로 전달되며, 늦게 도착한 ownership도 `EtcdLeaseHandle.markReleased()`로 exactly once 정리된다. `EtcdAsyncLifecycleTest.kt:31-258`의 single/group 취소·late ownership·executor 거부·supplier 실패가 이 경계를 고정한다. | PASS |
| 3. API/ABI | 새 public type, method, constructor, dependency는 없다. 최종 `checkBinaryCompatibility`는 `artifacts=16`, `ignored=1`, `unknown=0`이었다. README/KDoc/manual은 public 계약 불변이므로 `N/A`다. | PASS |
| 4. Backend/watch semantics | `JetcdWatchCallbackIntegrationTest.kt:23-177`은 created notification을 readiness barrier로 사용한다. callback 내부 blocking KV get, 느린 callback의 `PUT(v1) -> PUT(v2) -> DELETE`, watcher close 후 전달 중지와 새 watcher 재개를 각각 독립 검증한다. jetcd `0.8.6`에서 blocking callback이 `TimeoutException`으로 RED였고 `0.8.7`에서 3/3 GREEN이었다. | PASS |
| 5. Tests/flakiness | 고정 `delay` 없이 positive latch/future barrier를 사용한다. fake는 active ownership을 보유해 cleanup 없는 거짓 재획득을 막고, 실제 etcd는 direct async cancellation/contention/AOP scope와 virtual-thread interruption을 검증한다. 다만 healthy Colima에서 container host readiness가 간헐 실패한 현상은 [#884](https://github.com/bluetape4k/bluetape4k-leader/issues/884)로 분리했다. | 기능 PASS; 환경 P2 추적 |
| 6. Dependency/security/ops | `settings.gradle.kts`와 `.github/workflows/ci.yml`의 catalog ref를 동일한 immutable `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`로 원자 전환했다. candidate graph는 jetcd `0.8.7`, gRPC `1.82.0`, Netty `4.2.17.Final`, Vert.x `5.1.7`이다. TLS/auth, key layout, TTL/minLease/waitTime, caller-owned `Client` lifecycle은 바꾸지 않았다. | PASS |
| 7. CI/delivery | 기존 전체 build는 첫 실행에서 unrelated Redisson Toxiproxy host readiness timeout으로 실패했고, 해당 class 3/3 재현 통과 뒤 전체 build가 4분 38초에 성공했다. 최종 working tree의 `build`도 성공했고 JUnit artifact inventory는 4,369 tests, failures/errors/skipped 0이다. 최종 commit의 PR exact-head checks, review threads, mergeability read-back은 아직 실행 전이다. | 로컬 PASS; PR PENDING |

## 주요 findings와 disposition

| Severity | finding | disposition/evidence |
|---|---|---|
| P1 -> 해소 | action supplier가 future를 반환하기 전에 throw할 때 원래 cause와 lease cleanup이 직접 고정되지 않았다. | `EtcdAsyncLifecycleTest`에 single/group supplier throw, cause, unlock/revoke 1회, 동일 lock/slot 재획득을 추가했다. commit `84b8ac3b`. |
| P1 -> 해소 | 정상 async contention이 action을 호출하지 않고 `null`을 반환한다는 core 계약의 실제 etcd 증거가 없었다. | `EtcdAsyncLeaderElectorIntegrationTest`에 single/group contention 테스트를 추가했다. commit `84b8ac3b`. |
| P2 | healthy Colima에서 Toxiproxy와 etcd container가 내부 readiness를 완료했는데 mapped host endpoint probe가 timeout/reset될 수 있다. | 제품 결함과 분리해 [#884](https://github.com/bluetape4k/bluetape4k-leader/issues/884)에 재현·진단 DoD를 등록했다. timeout 연장이나 healthy VM 재시작으로 감추지 않는다. |

## Performance 및 stability 검토

- callback의 blocking은 raw jetcd 회귀를 검출하기 위한 한 테스트에만 격리했다. publisher 및 lifecycle 테스트는 blocking callback에 의존하지 않는다.
- readiness는 `WatchOption.withCreateNotify(true)`가 전달하는 created notification으로 고정하며 producer-before-listener race를 timeout 재시도로 숨기지 않는다.
- slow callback 순서 테스트는 첫 callback 진입을 확인한 후 후속 PUT/DELETE를 발행하고 명시적으로 해제한다. 실패 경로에서도 latch와 watcher를 정리한다.
- async acquisition과 action은 호출자 executor에서 순차 stage로 실행된다. initial executor rejection에는 backend call이 0이며, post-acquire rejection에는 unlock/revoke가 각각 정확히 한 번이다.
- returned cancellation, acquisition cancellation, action cancellation이 경합해도 cleanup은 lifecycle CAS와 `markReleased()`의 이중 idempotency 경계를 가진다.
- 모든 새 executor, watcher, client는 `finally` 또는 `use`로 닫는다. 테스트 synchronization을 위한 `Thread.sleep`/coroutine `delay`는 추가하지 않았다.

## 실행 모델 검증 matrix

| 경로 | single | group | 근거 |
|---|---|---|---|
| blocking | PASS | PASS | 기존 실제 etcd acquisition/contention/reacquire tests |
| `CompletableFuture` async | PASS | PASS | action cancellation, contention `null`, AOP `extendActiveLock`, late cleanup 및 rejection tests |
| coroutine suspend | PASS | PASS | 기존 cancellation/cleanup integration tests 포함 42-test 회귀 matrix |
| virtual thread | PASS | executor overload PASS | single interruption/reacquire contract, group executor overload contract |
| slot-aware async overload | N/A | N/A | Etcd public API에 concrete async `LeaderSlot` overload가 없다. |

## 검증 증거

| Check | 결과 |
|---|---|
| old catalog callback sensitivity | jetcd `0.8.6`, 1 test RED, `ExecutionException` cause `TimeoutException`, 3.3초 |
| candidate callback | 3/3 PASS, JUnit 5.2초, Gradle 18초, retry 없음 |
| publisher determinism | 4/4 PASS, JUnit 5.3초, 고정 `delay` 0건 |
| async lifecycle RED -> GREEN | 기존 구현 7개 중 6개 RED -> 수정 후 7/7 PASS |
| targeted fake/real/virtual | 14/14 PASS |
| execution-model regression matrix | 42/42 PASS, failures/errors/skipped 0 |
| review-gap targeted rerun | 15/15 PASS, failures/errors/skipped 0, Gradle 26초 |
| module full suite | clean `--rerun-tasks` **156/156 PASS**, failures/errors/skipped 0, JUnit 17.303초, Gradle 41초 |
| detekt | PASS, Gradle 9.6초 |
| ABI | PASS, `artifacts=16`, `ignored=1`, `unknown=0`, Gradle 6.8초 |
| repository full build | 첫 광역 실행 container readiness 1건 실패, 원인 class 3/3 PASS, 두 번째 광역 실행 4분 38초 PASS. 최종 working tree build PASS, JUnit artifact inventory 4,369/0/0/0 |
| final dependency graph | jetcd `0.8.7`, gRPC `1.82.0`, Netty `4.2.17.Final`, Vert.x `5.1.7`; 네 insight 모두 exit 0 |
| PR exact-head CI | PENDING |

## 최종 판정

구현 diff의 P0/P1은 0이다. public API와 운영 정책은 변하지 않았고, callback upgrade의 검출력과 async cleanup 계약은 RED/GREEN 및 실제 etcd로 증명됐다. 최종 로컬 gate는 통과했으며 PR exact-head CI만 남아 있으므로 현재 상태는 **PENDING (delivery)** 이다.
