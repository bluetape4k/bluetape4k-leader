# MongoDB async 정리 실패와 완료 순서 보강 계획

## 범위와 승인

- 이슈: [#916](https://github.com/bluetape4k/bluetape4k-leader/issues/916), 상위 Epic #925.
- 기준: `ded0504b2a26b360b8b488d68f052c144685683d`.
- 승인 범위: `fix/issue-916-mongo-cleanup`에서 수정·검증한 뒤 `develop` 대상 PR 생성까지. 머지와 auto-merge는 제외한다.
- 수정 대상: MongoDB dispatcher, single/group pre-action 정리 상태, 해당 테스트와 README.
- 새 의존성·공개 API 추가·다른 backend 변경은 제외한다. 네 backend 공통화는 #917에서 다룬다.

## 원인과 재사용 경계

`completeAfter`는 `RejectedExecutionException` 이외의 scheduler 실패를 처리하지 않아 별도 결과 future가 완료되지 않는다. 두 scheduler가 모두 실패하면 caller 스레드에서 blocking 정리를 실행하는 분기도 있다.

pre-action 정리는 획득 완료, action 시작, 정리 요청이 경합한다. 원래 action 제출 오류가 cleanup 완료 전에 노출되지 않도록 각 상태의 소유자를 고정해야 한다. 표준 `CompletableFuture.cancel()`의 즉시 취소 완료와 실제 lease 정리 완료는 구분한다.

#915의 Consul·etcd·Kubernetes 구현과 실패 주입 테스트를 참조한다. 일반 executor/future 변환 helper는 cleanup 소유권과 취소 전달을 대신하지 않으므로 새 wrapper를 도입하지 않는다.

## 작업 순서와 완료 조건

1. 기존 dispatcher 테스트를 기준으로 비표준 scheduler 예외를 주입한다.
   - 완료 조건: 수정 전 `TimeoutException`으로 재현되고 fixture/컴파일 실패가 아님을 확인한다.
2. single/group의 action 제출 거부와 늦은 획득에 대한 정리 완료 순서를 테스트한다.
   - 완료 조건: 정리 barrier가 닫힌 동안 결과 실패가 노출되지 않고, 해제 후 재획득하며 release는 한 번만 수행된다.
3. scheduler 실패를 terminal 상태로 처리하고 pre-action 정리 소유권을 수정한다.
   - 완료 조건: caller 스레드의 inline blocking 정리 없음, 원본 오류·suppressed chain 보존, cancellation 전달 보존.
4. 대상 회귀 테스트 후 MongoDB 전체 테스트, detekt, `checkBinaryCompatibility`를 순차 실행한다.
   - 완료 조건: 테스트 결과와 artifact별 ABI 분류를 확인하고 미확인 결과를 PASS로 기록하지 않는다.
5. 독립 코드 리뷰와 문서 대조 후 PR을 생성한다.
   - 완료 조건: 변경 범위의 P0/P1 없음, `Fixes #916`, milestone `1.1.0`, 담당자 `debop`, 이슈 라벨 반영 및 실제 PR 재조회.

## 검증 정책

- Testcontainers는 기존 `MongoDBServer.Launcher`를 재사용하고 다른 무거운 테스트와 병렬 실행하지 않는다.
- scheduler 예외·이중 실패·action 오류·cleanup 오류·취소를 각각 검증한다.
- 새 Kotlin 테스트는 bluetape4k assertions를 사용한다. barrier는 응답 시점과 스레드 소유권을 직접 증명하기 위한 것으로 일반 stress 반복과 구분한다.
- IDE diagnostics 도구를 사용할 수 없으면 변경 파일의 import/금지 패턴 검토와 compile/detekt로 보완하고 그 한계를 기록한다.
- CI는 PR의 실제 head와 변경 경로를 확인한다. 이 작업에서 Nightly workflow를 변경하거나 임의 dispatch하지 않는다.

## 실패·재실행과 보존

실패하면 해당 원인의 테스트부터 수정·재실행한다. 검토 뒤 head가 변경되면 영향을 받은 테스트와 리뷰를 갱신한다. 기존 worktree·파일·원격 브랜치를 삭제하지 않는다. PR 생성 뒤에도 머지 승인 전까지 브랜치와 이슈를 열린 상태로 유지한다.
