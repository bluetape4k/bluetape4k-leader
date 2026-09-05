# jetcd watch readiness와 async lease cleanup 교훈

## 문제와 결정

Issue [#880](https://github.com/bluetape4k/bluetape4k-leader/issues/880)은 중앙 catalog의 jetcd `0.8.7` 전환을 callback 동작과 Etcd leader lifecycle의 실행 가능한 계약으로 고정한다. 단순 버전 변경만으로는 callback deadlock, watcher 준비 경합, 반환 future 취소 뒤 lease 잔류를 검출할 수 없었다.

따라서 raw jetcd callback 경계와 library elector 경계를 분리해서 검증했다. raw 테스트는 upstream 행동 차이를, elector 테스트는 Bluetape4k가 소유하는 contention·cancellation·cleanup 의미를 담당한다.

## Watch 준비는 시간 대신 protocol event로 고정한다

watcher 생성 직후 임의 대기한 다음 PUT을 보내면 listener가 준비되기 전 event가 발생할 수 있다. 긴 sleep은 확률만 바꾸며 준비 완료를 증명하지 않는다.

jetcd watch에는 `WatchOption.withCreateNotify(true)`와 created response가 있다. 테스트는 이 response를 `CompletableFuture` readiness barrier로 변환하고, barrier 이후에만 producer를 실행한다. 같은 방식은 event publisher의 collector에도 적용한다. coroutine collector는 `CoroutineStart.UNDISPATCHED`로 구독을 설치하고 positive elected barrier로 경합 순서를 고정한다.

규칙은 다음과 같다.

- protocol이나 framework가 준비 완료 event를 제공하면 그것을 barrier로 사용한다.
- bounded timeout은 실패 진단 상한이지 readiness 구현이 아니다.
- 불발을 검증할 때만 짧은 negative timeout을 사용하고, 이후 future/collector를 명시적으로 취소한다.
- callback thread name이나 executor 개수 같은 dependency 구현 세부사항은 계약으로 고정하지 않는다.

## Dependency-sensitive RED는 실제 경계에서 얻는다

jetcd `0.8.6`의 watch callback 내부에서 같은 client의 blocking KV get을 실행하면 bounded timeout으로 실패했다. 동일 테스트는 `0.8.7`에서 통과했다. 이 RED/GREEN은 compile 실패나 container 준비 실패와 구분해야 한다.

callback 검증은 세 시나리오를 분리한다.

1. callback 내부 blocking KV 호출이 완료된다.
2. 느린 첫 callback 중 들어온 `PUT(v2)`와 `DELETE`가 `PUT(v1)` 뒤의 순서를 유지한다.
3. watcher close 뒤 이전 listener에는 전달되지 않고 같은 caller-owned client의 새 watcher는 다시 수신한다.

한 테스트에 모두 섞으면 deadlock, ordering, close race 중 어느 계약이 깨졌는지 알기 어렵다. 독립 시나리오와 한 container invocation을 함께 사용하면 진단력과 실행 비용을 균형 있게 유지할 수 있다.

## 반환 future 취소는 실제 작업과 backend ownership까지 이어져야 한다

`CompletableFuture.handle`과 `thenCompose`가 만든 dependent future는 caller cancellation을 source나 실제 action future로 자동 전달하지 않는다. leader API에서 반환 future만 취소되고 action이나 lease가 남으면 동일 lock의 다음 실행이 막힌다.

Etcd async lifecycle은 다음 세 상태로 정리한다.

- `WAITING`: acquisition 또는 action 제출 전이다. cancellation이 이 상태를 선점하면 cleanup owner가 된다.
- `STARTED`: action이 실제로 시작됐다. cancellation relay가 action future를 취소하고 action completion handler가 cleanup한다.
- `CLEANUP`: action을 시작하지 않는다. 늦게 도착한 ownership은 즉시 해제한다.

`WAITING -> STARTED`와 `WAITING -> CLEANUP` 중 하나만 CAS로 성공해야 한다. backend handle 자체도 `markReleased()`를 가져야 unlock/revoke가 중복 completion 경로에서 exactly once가 된다. cancellation test는 call count만 보지 않고 동일 lock/slot 재획득까지 확인해야 cleanup의 기능적 효과를 증명한다.

## 실패 경계마다 소유권을 구분한다

- 첫 executor 제출 거부: acquisition이 시작되지 않았으므로 backend call은 0이어야 한다.
- acquisition 후 두 번째 제출 거부: 이미 얻은 ownership을 해제하고 원래 `RejectedExecutionException`을 보존해야 한다.
- action supplier 동기 throw: watchdog을 닫고 ownership을 해제한 뒤 원래 cause를 result에 전달해야 한다.
- 정상 contention: 예외가 아니라 `null`이며 action supplier는 호출하지 않는다.
- caller cancellation: returned future뿐 아니라 실제 action future도 취소되고 동일 lock/slot이 재획득 가능해야 한다.

이 경계를 하나의 generic failure handler로 뭉치면 획득 전 불필요한 cleanup이나 획득 후 누락을 만들기 쉽다. acquisition handle의 존재와 lifecycle state를 함께 사용한다.

## Catalog 변경은 원자적이고 되돌릴 수 있어야 한다

local Gradle 설정과 CI가 다른 immutable catalog ref를 사용하면 로컬 GREEN과 hosted 결과가 다른 dependency graph를 검증한다. `settings.gradle.kts`와 `.github/workflows/ci.yml`의 ref를 같은 patch와 commit에서 바꾸고, rollback도 두 파일을 함께 되돌린다.

최종 증거에는 direct dependency 하나만이 아니라 jetcd, gRPC, Netty, Vert.x selected version과 selection reason을 남긴다. TLS/auth, key layout, lease 기본값, caller-owned client lifecycle처럼 변경하지 않은 운영 경계도 명시한다.

## 환경 readiness 실패를 제품 결함이나 성공으로 오인하지 않는다

healthy Colima에서도 container 내부 서비스가 준비됐지만 mapped host endpoint probe가 timeout 또는 reset될 수 있었다. 같은 현상이 Toxiproxy와 etcd에서 관찰되어 [#884](https://github.com/bluetape4k/bluetape4k-leader/issues/884)로 분리했다.

재실행 성공은 최초 실패를 지우지 않는다. container log, mapped endpoint, host listener, Docker/Colima 상태를 보존하고 제품 테스트 본문 진입 전 실패인지 구분한다. timeout 연장이나 healthy VM 재시작은 원인 규명이 아니다.

## 재사용 체크리스트

- listener/collector 준비를 positive event로 증명했는가?
- dependency-sensitive RED가 정확히 이전 dependency 행동에서 실패했는가?
- callback blocking, ordering, close/restart를 독립적으로 검증했는가?
- returned cancellation이 acquisition과 실제 action으로 전달되는가?
- late ownership, executor rejection, supplier throw가 exactly-once cleanup으로 수렴하는가?
- 정상 contention이 action 미호출과 `null` 반환을 함께 보존하는가?
- fake가 cleanup 없이도 재획득되는 거짓 양성을 허용하지 않는가?
- local/CI catalog ref와 dependency graph가 동일한가?
- caller-owned client, key layout, lease policy, public ABI가 보존되는가?
- container readiness 실패를 별도 stability finding으로 추적했는가?
