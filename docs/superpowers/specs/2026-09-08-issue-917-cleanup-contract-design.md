# #917 비동기 cleanup 공유 계약

## 범위와 선택

승인된 #925 순서에 따라 #916의 `1c26c291` 위에서 진행한다. base `fix/issue-916-mongo-cleanup`,
head `refactor/issue-917-shared-cleanup`이며 머지하지 않는다.
목적은 네 backend의 dispatcher 수정 누락을 같은 계약 테스트로 탐지하고 테스트 중복을 제거하는 것이다.

| 대안 | 이점 | 비용과 결정 |
|---|---|---|
| 기존 core testFixtures의 공유 contract test | production API/의존성/소유권 불변, 동일 회귀를 네 모듈에서 실행 | production clone은 남음. 이슈의 허용 방식이며 이번 선택 |
| core production 지원 API | 알고리즘 중복도 제거 | Kotlin internal cross-module 접근 불가, 지원 API와 ABI 경계 확대. 이번에는 제외 |
| 빌드에서 공유 소스 생성 | backend internal 접근 유지 | 패키지 재작성 및 산출물 중복 위험, source jar/debug 경로 관리 필요. 제외 |

공통 계약의 소유자는 이 저장소 `leader-core/src/testFixtures/.../contract`다. 기존 backend의
`testImplementation(testFixtures(project(...)))`를 사용하며 새 dependency와 production API는 추가하지 않는다.
upstream helper 변경도 제외한다. `DirectExecutor`와 common-pool은 blocking cleanup 격리를 대체하지 않는다.

## 현재 계약 비교

| backend | execute | 정리 예약/실패 | 취소와 완료 | backend별 보존 |
|---|---|---|---|---|
| Consul | CompletableFuture<Unit> | 자체 virtual thread, Throwable fallback, 이중 실패 terminal | dispatcher 결과 취소와 lease 완료 별도 | session lifecycle 및 AsyncLeaseCleanupBarrier |
| etcd | CompletableFuture<Unit> | 같은 정책, backend별 thread 이름 | 같은 계약 | lease revoke 및 barrier |
| Kubernetes | CompletableFuture<Unit> | 같은 정책, backend별 thread 이름 | 같은 계약 | resourceVersion/holder 및 barrier |
| MongoDB (#916) | CompletableFuture<Unit> | 같은 정책, backend별 thread 이름 | 같은 계약 | single/group 획득 기록 dependent stage와 기존 rejection cleanup |

모두 원래 action 오류를 우선하고 cleanup/dispatch 오류를 suppressed로 보존한다. 이중 scheduler 실패는
실제 unlock의 증거가 아니다. backend별 barrier·callback은 상태 머신이 다르므로 통합하지 않는다.

## 실패 모드와 수용 조건

1. 비표준 scheduler 예외를 놓쳐 result가 미완료됨: 같은 failure-injection 계약으로 네 구현 검사.
2. 두 scheduler 실패 후 inline cleanup: cleanup 미실행 및 원래/추가 오류 체인 검사.
3. 취소 완료 스레드에서 cleanup blocking: caller 종료와 cleanup 전 result 미완료를 latch로 구분.
4. action/cleanup/dispatch에 같은 Throwable 객체 사용: 자기 suppression 없이 terminal 검사.
5. 실행기 중복 실행: cleanup CAS가 한 번만 실행됨을 검사.

기존 single/group 정상·취소·action 제출 거부 integration 테스트는 유지하고 네 모듈을 순차 실행한다.
공유 suite 도입 후 기존 중복 scheduler 테스트만 제거한다. 기존 backend 특화 테스트는 삭제하지 않는다.
공개 ABI 및 runtime dependency 불변을 검증한다. stacked base는 CI trigger 대상이 아니므로 자동 CI 부재를
통과로 표시하지 않고 PR 생성 후 PENDING으로 남긴다. base 변경 및 dispatch는 하지 않는다.

## DoD

공유 contract suite가 네 backend에서 동일하게 실행되고, 중복 테스트 제거 뒤 전체 모듈 검증이 통과한다.
spec/plan/lesson 및 검토 근거를 커밋하고 승인된 base/head로 PR을 생성한다. CI와 머지 상태는 별도 보고한다.
