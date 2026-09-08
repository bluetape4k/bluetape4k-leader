# 비동기 실행 경계를 바꿀 때 소유권과 context를 함께 검증한다

## 원인과 결정

#923의 ZooKeeper async 구현은 caller executor에서 action future를 join했다.
같은 single-thread executor에 action 작업을 제출하면 유일한 스레드가 대기하여 실행되지 않았다.
실제 ZooKeeper fixture에서 single/group 모두 `TimeoutException`으로 재현했다.

기존 `VirtualThreadExecutor`의 한 소유 작업에서 동기 `runIfLeader`를 실행하도록 변경했다.
acquire/release 스레드는 유지하고 caller executor에는 action 시작만 제출한다.
새 executor pool이나 의존성은 추가하지 않는다. runtimeClasspath의 JDK 25 provider가
`leader-core -> bluetape4k-core` 경로에 있음을 dependencyInsight로 확인했다.

## 구현 검토에서 놓친 계약

최초 수정은 교착을 없앴지만 action 스레드의 lock-handle context를 잃었다. 기존 모듈 110개 테스트는
통과했으나 추가한 context 검증 두 건은 실패했다. acquire/release만 같은 스레드이면 충분하다는 가정이 틀렸다.
`AopScopeAccess.withPushedSync`와 group capture를 action의 최초 호출에 전달하고 반환 시 제거했다.
임의의 후속 future callback까지 context가 전파된다는 보장은 추가하지 않았다.

취소도 제출 future와 실제 action future를 구분해야 한다. 기존 `LeaderFutureBridge`와 두 relay로
대기 중 제출 취소 및 시작된 action 취소를 연결했다. 제출이 executor 큐에 남아 있어도 취소 뒤에는
action을 실행하지 않으며, owner 작업은 join에서 빠져나와 정리할 수 있다.

## 검증과 재발 방지

- single/group 교착 RED 2건, context 누락 RED 2건을 각각 수정 후 검증했다.
- 제출 거부·action 실패 뒤 즉시 재획득, 제출 대기 중 취소와 늦은 action 차단을 검증했다.
- cleanup 실패 주입으로 기존 best-effort 성공 및 원래 action 오류 정책을 확인했다.
- 테스트의 Awaitility import를 추정하여 컴파일에 실패한 뒤 기존 테스트의 실제 import로 수정했다. 다음에는 인접 코드의 import를 먼저 확인한다.

실행 경계를 바꾸는 리뷰에는 스레드 소유권뿐 아니라 thread-local context, queued submission,
실제 action future, terminal result와 cleanup 완료를 각각 넣는다. 기존 전체 테스트의 성공만으로
새 경계의 계약이 증명됐다고 간주하지 않는다.
