# #923 ZooKeeper 비동기 실행 경계

## 범위와 계약

#925 승인된 순서, base `develop`의 `ded0504b`, head `fix/issue-923-zookeeper-executor`.
single/group async의 caller executor 자기 대기만 수정한다. 머지와 새 의존성은 제외한다.

현재 caller executor의 유일한 스레드가 action future를 join하면 동일 executor에 제출한 action이 실행되지 않는다.
기존 `VirtualThreadExecutor`에서 동기 `runIfLeader`를 실행해 Curator acquire/release 소유 스레드를 유지한다.
action은 caller executor에서 시작하며 owner 스레드가 완료를 기다린다. cancellation bridge는 제출 future와
실제 action future를 모두 취소하도록 연결한다. 단순 thenCompose는 취소를 안쪽 future까지 전달하지 않는다.

## 순서와 검증

1. GNO/live #923, single/group 소스, 기존 취소 테스트와 runtime dependency를 확인한다.
2. 실제 ZooKeeper fixture에서 같은 single-thread executor action을 사용하는 두 경로로 timeout RED를 확인한다.
3. owner 작업과 caller action 제출을 분리한다. 기존 best-effort release 오류 정책은 유지한다.
4. 정상 완료·원본 실패·제출 거부 뒤 즉시 재획득, 제출 대기 중 취소, 기존 contention/cancellation 회귀를 검증한다.
5. module test/detekt/ABI를 순차 실행한다. README 두 언어와 lesson에 실행 및 취소 경계를 기록한다.
6. 최종 검토, commit/push, PR 생성, exact-head CI 확인. 일괄 머지는 별도 승인 시점까지 보류한다.

실패하면 해당 회귀 테스트부터 수정한다. 반환 future의 명시적 cancel은 즉시 완료하므로 cleanup 완료 신호로
간주하지 않고 재획득으로 별도 검증한다. 새 소유 thread pool을 생성하지 않고 기존 executor를 재사용한다.
