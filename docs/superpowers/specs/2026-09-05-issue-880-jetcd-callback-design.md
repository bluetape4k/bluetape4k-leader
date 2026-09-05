# Issue #880 jetcd callback·watch·lease 회귀 검증 설계

## 문서 상태

- 상태: 사용자 승인 방향과 6개 관점 inline 검토를 반영한 설계
- 작성일: 2026-09-05
- 대상 저장소: `bluetape4k/bluetape4k-leader`
- 대상 모듈: `leader-etcd`
- 이슈: [#880](https://github.com/bluetape4k/bluetape4k-leader/issues/880)
- 기준 커밋: `f9c084c241b5ac87a4b644f64ef47da55d71fbcb` (`develop`)
- 작업 branch: `test/issue-880-jetcd-callback`
- 승인 범위: 중앙 catalog `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b` 채택과 jetcd `0.8.7` callback·watch·lease 회귀 검증
- 제외 범위: public API 변경, etcd 운영 정책 변경, publish/release/tag, merge

## SPW-01 — 독자·목적·근거 고정

이 문서는 `leader-etcd` 구현자와 리뷰어가 의존성 전환과 회귀 테스트의 경계를 확인하도록 작성한다. 다음 근거를 기준으로 삼는다.

| 근거 | 확인한 계약 |
|---|---|
| [Issue #880](https://github.com/bluetape4k/bluetape4k-leader/issues/880) | jetcd `0.8.7`, callback 순서, watch lifecycle, sync/async/suspend lease 검증 요구 |
| [jetcd PR #1559](https://github.com/etcd-io/jetcd/pull/1559) | 기본 channel을 `VertxChannelBuilder`에서 `NettyChannelBuilder`로 바꾸어 callback 안의 blocking call과 ordered callback 실행을 지원 |
| 중앙 catalog `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b` | `jetcd-core 0.8.7`, gRPC `1.84.0`, Vert.x `5.1.7`을 포함한 다음 dependency train |
| `EtcdLeaderElectionEventPublisher.kt` | jetcd callback에서 event 목록을 만든 뒤 별도 coroutine으로 owner를 재검증 |
| `AsyncLeaderElector.kt` | 반환 future 취소를 acquisition, action, lease cleanup으로 전달하는 공통 계약 |
| `EtcdLeaderElector.kt`, `EtcdLeaderGroupElector.kt` | 현재 `CompletableFuture.supplyAsync { action().join() }` 구현은 반환 future 취소를 action future로 전달하지 않음 |
| 기존 `leader-etcd` 테스트 | 기본 catalog와 후보 catalog에서 각각 137 tests 통과 |

확인되지 않은 주장은 설계에 포함하지 않는다. 특히 jetcd callback의 순서 보장은 `scope.launch` 이후 coroutine 처리 순서까지 보장하지 않으며, 현재 Leader의 deadlock이나 event loss가 재현되었다고 가정하지 않는다.

## SPW-02 — 문제와 선택

현재 `settings.gradle.kts`와 `.github/workflows/ci.yml`은 catalog commit `850959d0ea5f76ac7e2c442400f47653d5f95eed`를 고정하므로 기본 graph에서 `jetcd-core 0.8.6`이 선택된다. 로컬에서 catalog path만 주입하면 `0.8.7` 테스트는 가능하지만 hosted CI와 실제 기본 build는 계속 `0.8.6`을 사용한다.

### 대안 A — 중앙 catalog pin을 갱신하고 실제 etcd 회귀 테스트를 추가한다 (선택)

두 catalog 진입점을 `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`로 맞춘다. 실제 etcd watch callback에서 blocking KV 호출, callback 직렬화, close/restart를 검증하고 기존 elector 테스트로 lease lifecycle을 다시 확인한다.

- 장점: 로컬 기본 build와 hosted CI가 같은 `0.8.7` graph를 검증한다.
- 단점: jetcd 외 Spring Boot, Exposed, gRPC 등 중앙 train 전체가 바뀌므로 저장소 전체 회귀 검증이 필요하다.

### 대안 B — 테스트 명령에만 catalog path를 주입한다

- 장점: 기본 dependency graph를 바꾸지 않는다.
- 단점: PR이 merge되어도 Leader의 기본 artifact는 `0.8.6`을 사용하므로 Issue #880 완료 조건을 충족하지 못한다.
- 판정: 거부.

### 대안 C — callback 이후 처리를 production serial queue로 바꾼다

- 장점: `scope.launch` 이후 처리 순서를 별도로 고정할 수 있다.
- 단점: 현재 event loss나 잘못된 event 의미가 재현되지 않았고, 불필요한 hot-path와 lifecycle 변경을 만든다.
- 판정: 거부. 결정론적 RED가 Leader 자체 결함을 증명할 때만 최소 수정안을 다시 검토한다.

## 설계

### 1. Dependency graph

`settings.gradle.kts`의 기본 catalog ref와 `.github/workflows/ci.yml`의 `BLUETAPE4K_DEPENDENCIES_CATALOG_REF`를 같은 immutable SHA로 변경한다. 다음 두 graph를 증거로 남긴다.

1. `jetcd-core 0.8.7` 직접 선택
2. 함께 선택된 gRPC/Netty/Vert.x 버전과 dependency conflict 결과

catalog commit에는 여러 모듈이 사용하는 버전 변경이 포함되므로 `leader-etcd` 테스트만으로 채택을 확정하지 않는다. 전체 build와 CI summary가 함께 통과해야 한다.

### 2. jetcd callback fixture

새 실제 etcd integration fixture는 raw `Watch.Listener` 경계를 검증한다.

watch 생성 자체는 server-side subscription 완료를 뜻하지 않는다. 모든 fixture는 `WatchOption.withCreateNotify(true)`의 created response를 readiness barrier로 사용한 뒤 PUT을 시작한다. blocking call, ordered delivery, close/restart는 서로 다른 테스트로 분리한다.

1. blocking-call 테스트는 callback 안에서 같은 `Client`의 `KV.get(...).get(timeout)`을 실행하고 응답값을 확인한다.
2. ordered-delivery 테스트는 첫 callback을 `CountDownLatch`로 붙잡은 뒤 두 번째 PUT을 보내고, 두 번째 callback이 먼저 진입하지 않음을 확인한다. 첫 callback을 해제한 뒤 callback 관찰 순서가 PUT 순서와 같은지 확인한다.
3. close/restart 테스트는 callback과 `Watcher.close()`를 경합시킨 뒤 close 이후 PUT이 기존 listener로 전달되지 않는지 확인한다. 새 watcher는 별도 created barrier를 통과한 뒤 후속 PUT을 받아야 한다.

고정 `Thread.sleep`이나 coroutine `delay`는 동기화 수단으로 사용하지 않는다. bounded `await`와 future timeout은 실패를 무한 대기로 바꾸지 않기 위한 상한으로만 사용한다.

### 3. Leader event 의미

`EtcdLeaderElectionEventPublisherIntegrationTest`의 collector는 `CoroutineStart.UNDISPATCHED`로 즉시 구독한다. 기존 `delay(250)`를 제거하고 다음 의미를 검증한다.

- 실제 owner 생성은 `Elected(lockName)` 한 건으로 관찰된다.
- owner 삭제는 같은 lock의 `Revoked(lockName)`으로 관찰된다.
- queued contender의 중간 key event는 별도 leader election으로 노출되지 않는다.
- publisher close 이후 event가 방출되지 않고 caller-owned `Client`는 계속 사용할 수 있다.

raw callback 순서와 Leader event 의미는 별도 테스트로 유지한다. upstream callback 직렬화를 Leader event 순서의 유일한 근거로 사용하지 않는다.

### 4. Lease lifecycle 검증

기존 실제 etcd 및 fake-client 테스트를 acceptance matrix로 묶고 부족한 async 경로만 보강한다.

| 진입점 | 검증 내용 |
|---|---|
| sync | lease grant, lock, keep-alive, unlock, revoke, contention timeout 뒤 재획득 |
| async | 단일/group action 완료·실패 뒤 재획득, 반환 future 취소가 실제 action future로 전달되고 cleanup 뒤 재획득 가능 |
| suspend | keep-alive, cancellation/timeout 뒤 unlock·revoke와 재획득 |
| virtual thread | blocking delegate 완료 뒤 재획득 |

`EtcdLeaderElector`와 `EtcdLeaderGroupElector`의 현재 async wrapper는 `CompletableFuture.supplyAsync` 안에서 action future를 `join()`한다. 반환 future의 `cancel()`은 이 source task나 실제 action future를 자동으로 취소하지 않으므로 `AsyncLeaderElector` KDoc 계약과 맞지 않는다. 결정론적 RED로 확인한 뒤 기존 `LeaderFutureBridge`와 backend별 async lifecycle 패턴을 재사용해 다음 상태를 보장한다.

- 취소 전 action이 정해졌으면 실제 action future를 취소한다.
- acquisition이나 action 대기 중인 worker가 있으면 협력적 중단을 전달한다.
- action 시작, 취소, executor 거부가 경합해도 lease cleanup은 한 경로만 소유한다.
- cleanup 뒤 같은 lock 또는 group slot을 다시 획득할 수 있다.
- 원래 `CancellationException`과 `RejectedExecutionException`을 다른 실패로 바꾸지 않는다.

public API와 callback 실행 모델은 바꾸지 않는다. 이 lifecycle 보정이 별도 public contract나 공통 core 변경을 요구하면 구현을 중단하고 설계를 다시 승인받는다.

## 실패 모드와 대응

1. **callback 안의 blocking KV 호출이 timeout된다.** `0.8.6`에서 RED를 확인하고 catalog 전환 뒤 같은 테스트가 GREEN인지 비교한다. `0.8.7`에서도 실패하면 graph와 channel builder를 먼저 확인한다.
2. **callback 순서 테스트가 하나의 WatchResponse batch만 검증한다.** 첫 callback 진입을 확인한 다음 두 번째 PUT을 보내서 서로 다른 delivery 시점을 만든다.
3. **close 이후 negative assertion이 서버 지연 때문에 우연히 통과한다.** close된 watcher와 새 watcher를 동시에 비교하고, 새 watcher가 후속 PUT을 받는 positive assertion을 함께 둔다.
4. **event collector가 늦게 구독해 첫 event를 놓친다.** `CoroutineStart.UNDISPATCHED`로 collector 등록을 완료한 뒤 lock 작업을 시작한다.
5. **전역 catalog 변경이 다른 모듈을 깨뜨린다.** targeted test 다음 전체 build를 실행하고, 실패하면 catalog pin 두 곳을 이전 SHA로 되돌린 뒤 원인을 별도 분리한다.
6. **원인 미확인 재시도에서만 통과한다.** 첫 실패 로그를 보존하고 원인을 설명하기 전에는 PASS로 기록하지 않는다.
7. **async 취소와 action 시작이 경합해 lease가 남는다.** action 시작과 cleanup 소유권을 하나의 원자 상태로 관리하고, 취소된 action과 동일 lock/slot 재획득을 함께 검증한다.
8. **executor가 lease 획득 뒤 작업 제출을 거부한다.** 제출 지점을 cleanup 소유권 안에 두고 원래 `RejectedExecutionException`과 재획득을 검증한다.

## 호환성·운영·rollback

- public Kotlin/JVM API, KDoc, README, examples에는 변경이 없다.
- etcd key layout, lease TTL, timeout 기본값, client ownership은 유지한다.
- 중앙 catalog 전환은 compile/runtime graph를 넓게 바꾸므로 ABI dump뿐 아니라 전체 build와 CI matrix를 확인한다.
- rollback은 두 catalog pin을 함께 `850959d0ea5f76ac7e2c442400f47653d5f95eed`로 되돌리는 것이다. 둘 중 하나만 되돌리는 상태는 허용하지 않는다.
- publish, release, tag, workflow dispatch, merge는 별도 승인 경계다.

## Acceptance criteria

- [ ] 기본 `dependencyInsight`에서 `io.etcd:jetcd-core:0.8.7`을 확인한다.
- [ ] gRPC/Netty/Vert.x 조합과 선택 사유를 기록한다.
- [ ] callback 내부 blocking KV 호출이 bounded timeout 안에 완료된다.
- [ ] 느린 첫 callback과 연속 PUT에서도 callback 순서가 보존된다.
- [ ] close 뒤 기존 listener는 event를 받지 않고 새 watcher는 event를 받는다.
- [ ] publisher의 PUT/DELETE event 의미와 queued contender 억제가 고정 sleep 없이 통과한다.
- [ ] sync/async/suspend/virtual-thread 단일 및 해당 group lease lifecycle이 timeout·취소·실패 뒤 재획득을 보장한다.
- [ ] async 반환 future 취소가 실제 action future로 전달되고 cleanup 뒤 동일 lock/slot을 재획득한다.
- [ ] executor 거부가 원래 예외를 보존하고 획득한 lease를 남기지 않는다.
- [ ] `leader-etcd` 전체 테스트가 첫 실행에서 통과한다.
- [ ] 전체 build, detekt, ABI/API, `git diff --check`가 통과한다.
- [ ] 7-Tier 리뷰에서 P0=0, P1=0이고 exact-head PR CI가 통과한다.

## DoD

설계·계획 추적성, RED/GREEN 증거, dependency graph, 테스트 수와 실패 수, 전체 build·정적 검사·ABI 결과, review finding 처리, PR head와 CI 결론을 모두 기록한다. merge는 fresh exact-head 승인 전까지 `PENDING`으로 남긴다.

## SPW-03..SPW-05 — 문체·추적성·읽기 검토

- `SPW-03`: 한국어 기술 문서 register와 `KO-01..KO-07`을 적용하고 identifier, SHA, command, URL을 그대로 유지한다.
- `SPW-04`: Issue #880, jetcd PR #1559, 중앙 catalog diff, 현재 source/test 경계와 acceptance criteria를 대조한다.
- `SPW-05`: Markdown 구조, 표, 목록, link, scope/rollback/DoD를 다시 읽고 placeholder와 상충 문장을 허용하지 않는다.

## 설계 검토 결과

독립 native lane 세 개는 3분 bounded wait와 종료 요청에도 결과를 반환하지 않아 중단했다. 사용자 지침에 따라 여섯 관점을 inline으로 다시 검토했다.

| 우선순위 | 관점 | 근거 | 반영 |
|---|---|---|---|
| P1 | stability | `watch()` 반환만으로 server-side create 완료를 알 수 없음 | `withCreateNotify(true)` created barrier를 모든 watch fixture에 추가 |
| P1 | stability | blocking call과 첫 callback 지연을 한 테스트에 섞으면 timeout 원인을 구분할 수 없음 | blocking, ordered delivery, close/restart를 세 테스트로 분리 |
| P1 | developer/API | `AsyncLeaderElector`는 반환 future 취소 전파를 요구하지만 Etcd 단일/group은 `supplyAsync` source와 action을 연결하지 않음 | 단일/group cancellation relay, exactly-once cleanup, executor 거부 테스트를 설계에 포함 |
| P2 | performance | ordered callback 테스트가 긴 timeout을 반복하면 module suite 시간을 늘림 | latch timeout은 진단 상한으로만 사용하고 실제 Testcontainers invocation은 한 번에 실행 |
| P2 | security | Netty 기본 channel 전환은 TLS/auth 설정 경계를 바꿀 수 있음 | public client 설정을 바꾸지 않고 resolved graph와 기존 client integration suite로 호환성 확인 |
| P2 | operator/Ops | catalog rollback 두 pin 중 하나만 복구하면 local/CI graph가 달라짐 | 두 pin의 원자적 rollback과 일치 검사를 명시 |
| P3 | user/caller | public 동작과 설정 변경이 없으므로 README 변경은 오히려 범위를 흐림 | README/KDoc 변경을 N/A로 유지하고 PR에 검증 범위만 기록 |

최종 통합 판정은 `P0=0`, `P1=0`이다. P1 세 건은 위 설계에 반영했고 P2/P3는 계획의 검증·N/A 근거로 추적한다.
