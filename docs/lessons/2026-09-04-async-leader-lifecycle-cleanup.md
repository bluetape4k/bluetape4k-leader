# 비동기 leader lifecycle의 취소와 executor 거부 정리

## 맥락

Issue #857과 #858은 `runAsyncIfLeaderResult`가 반환한 future를 취소하거나,
lock을 획득한 직후 executor가 작업 제출을 거부할 때 실제 action과 lease의
lifecycle이 끝나지 않는 문제를 다룹니다. 반환 future의 terminal 상태만 바뀌고
내부 action이 계속 실행되면 lock 재획득이 지연됩니다. Redis backend에서는
획득과 action 제출 사이의 실패 때문에 이미 얻은 lease나 group permit이 남을 수
있습니다.

Issue #890에서는 같은 계약이 nullable `runAsyncIfLeader` 경로에 적용되지 않은
사실을 확인했습니다. 결과형 API만 cancellation relay를 사용하고 nullable API는
`CompletableFuture.supplyAsync { action().join() }`을 직접 반환해, 호출자가 반환
future를 취소해도 실제 action future가 계속 실행됐습니다.

Issue #900에서는 action future의 terminal callback이 lease cleanup을 어느
스레드에서 실행하는지 점검했습니다. 비동기 suffix가 없는 `handle`은 future를
완료한 스레드에서 callback을 실행할 수 있습니다. callback 안의 `minLeaseTime`
대기와 backend 요청 때문에 사용자 event loop가 멈출 수 있었습니다.

## 원인

- 결과 future의 취소 대상이 바깥쪽 source future에만 연결되어 실제 action
  future까지 전달되지 않았습니다.
- `thenComposeAsync` 계열 pipeline은 lock 획득 뒤 executor 제출이 실패하면 action
  callback에 진입하지 않으므로 callback 내부의 release 경로도 실행하지 않습니다.
- 취소와 정상 완료가 경쟁할 때 여러 callback이 cleanup을 시작할 수 있으므로,
  cleanup owner를 하나로 고정하지 않으면 중복 release 위험이 생깁니다.
- action 시작 여부와 cleanup 여부를 서로 다른 원자 값으로 관리하면 cancellation과
  action 제출이 모두 성공했다고 판단하는 TOCTOU 경쟁이 생깁니다.
- `CompletableFuture.handle`은 실패 시 value를 `null`로 전달합니다. terminal
  mapper가 value를 non-null `T`로 선언하면 lambda 진입 전에 Kotlin NPE가 발생해
  원래 cancellation이나 backend 예외를 가립니다.
- action 완료와 cleanup 완료의 순서는 필요하지만, 같은 스레드에서 연속 실행할
  필요는 없습니다. 두 조건을 동일시해 사용자 completion thread가 blocking
  cleanup까지 떠맡았습니다.

## 결정

- 결과 변환과 backend pipeline 사이에 cancellation relay를 두고, 실제 action
  future가 정해지는 즉시 취소 대상을 연결합니다.
- 결과형과 nullable API가 같은 relay 규칙을 사용합니다. 바깥 source future와
  실제 action future를 모두 relay에 연결해 어느 경로에서도 취소가 끊기지 않게
  합니다.
- lock 획득 이후에는 action 실행 여부와 관계없이 terminal 경로가 lease cleanup을
  소유합니다. executor rejection도 cleanup이 끝난 뒤 원래 예외로 완료합니다.
- cancellation, action 완료, 제출 실패가 경쟁해도 원자적 owner가 cleanup을 정확히
  한 번만 시작하도록 합니다.
- Consul, Hazelcast, Kubernetes 단일/group 경로는 `WAITING -> STARTED`와
  `WAITING -> CLEANUP`을 하나의 CAS 상태 전이로 통합합니다. action과 cleanup 중
  하나만 lifecycle 소유권을 얻습니다.
- `LeaderFutureBridge.map`과 `flatMap`의 terminal value 계약을 `T?`로 바꿔
  `value == null && failure != null`인 표준 `CompletableFuture` 실패를 보존합니다.
- Etcd, Consul, Kubernetes의 단일/group async 경로는 action completion callback에서
  cleanup을 caller executor와 독립된 backend 전용 virtual thread로 넘깁니다.
  thread 시작이 거부되면 fallback 경로에서도 cleanup을 exactly-once로 실행하고,
  cleanup이 끝난 뒤에만 결과 future를 완료합니다. action과 cleanup이 모두 실패하면
  action 실패를 주 예외로 유지하고 cleanup 실패를 suppressed exception으로 남깁니다.

## 결과와 검증

반환 future를 취소하면 실제 action future도 취소되고, cleanup 완료 뒤 같은 lock을
다시 획득할 수 있습니다. backend별 회귀 테스트는 action 미실행, 원래
`RejectedExecutionException`, lease/permit 회수까지 검증합니다.

- RED: 실제 action이 취소되지 않고 재획득이 `null`이 되는 동작을 재현했습니다.
- GREEN: 최종 source 기준 targeted test 275개가 통과했습니다. 구성은 core 39,
  Exposed JDBC 174, Consul 20, Hazelcast 28, Kubernetes executor contract 4,
  MongoDB 2, Lettuce 4, Redisson 4입니다.
- Kubernetes PR task graph에서 unit 16개와 K3s 103개가 통과했고,
  `leader-k8s/build/reports/kover/report.xml`을 같은 invocation에서 생성했습니다.
- 영향 모듈 9개의 `compileTestKotlin`, 전체 `detekt`, binary compatibility 검사가
  통과했습니다. ABI inventory는 artifacts 16, ignored 0, unknown 0이며 분류되지
  않은 public incompatibility가 없습니다.
- Kover 정적 계약 validator와 단위 테스트 9개, `actionlint`, diff whitespace 검사를
  통과했습니다. hosted exact-head CI는 PR push 뒤 별도로 확인합니다.

Issue #890 회귀 검증에서는 Local blocking/async, ZooKeeper, DynamoDB의 단일/group
nullable API를 함께 확인했습니다. core 1,045개, ZooKeeper 102개, DynamoDB 121개
전체 테스트와 `detekt`, binary compatibility 검사가 통과했습니다. 취소 전에 lock을
얻지 못한 경우에는 action을 시작하지 않고, executor가 작업을 거부하면 원래
`RejectedExecutionException`을 즉시 보존하는 계약도 core contract test로
고정했습니다.

Issue #900 회귀 검증은 이름이 지정된 single-thread event loop에서 action future를
완료한 다음, blocking cleanup과 별개로 probe task가 진행되는지 확인합니다. Etcd
161개, Consul 157개, Kubernetes unit 20개와 K3s 103개가 모두 통과했습니다.
cleanup 실행을 거부하는 test executor에서도 각 backend dispatcher의 cleanup이 한
번만 실행되고 결과 future가 terminal 상태에 도달했습니다. cleanup까지 실패하는
경우에는 원래 action 실패가 보존됩니다. 전체 `detekt`와 binary
compatibility gate도 통과했으며 ABI inventory는 artifacts 16, ignored 1,
unknown 0입니다. ignored 1건은 기존 Lettuce compiler-generated synthetic
accessor 분류입니다.

## 놓친 가정과 향후 지침

`CompletableFuture.cancel()`은 연결된 비동기 작업 전체를 자동으로 취소하지
않습니다. future adapter를 추가하거나 수정할 때는 결과형과 nullable API를 모두
목록화하고, 반환 future, acquire future, 실제 action future, watchdog, lease
cleanup의 ownership을 각각 확인해야 합니다.
회귀 테스트는 `isCancelled`만 검사하지 말고 action 취소와 동일 lock/slot 재획득을
함께 검증합니다. acquire 뒤 비동기 제출이 있는 backend는 executor rejection을
별도 terminal 경로로 취급하고, 원래 실패가 관찰되기 전에 cleanup이 끝나는지도
검증합니다.

completion thread 격리 테스트는 임의의 `sleep` 대신 cleanup 진입·해제 latch를
사용합니다. cleanup이 막힌 동안 event-loop probe가 진행되고 결과 future는 아직
완료되지 않았음을 함께 검증해야 합니다. cleanup executor rejection 테스트는
release call count뿐 아니라 fallback 뒤 terminal completion도 확인합니다.

리뷰 운영에서도 P1 발견을 issue-only closeout으로 넘기지 않습니다. P0/P1은 현재
delivery를 막고 먼저 수정하며, 독립 review lane이 응답 없이 멈추거나 실행되지
않으면 lane을 회수해 inline으로 동일 범위를 검토하고 증거를 다시 실행합니다.
이번 재검토에서는 이 규칙으로 lifecycle TOCTOU와 terminal mapper NPE를 추가로
찾아 수정했습니다.
