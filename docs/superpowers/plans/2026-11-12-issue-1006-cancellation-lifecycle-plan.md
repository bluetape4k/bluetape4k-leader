# #1006 비동기 취소 lifecycle 관찰 경계 계획

## 범위와 승인

Milestone `1.1.0`의 #1006 후속 수정이다. 기준은 `origin/develop`의
`d2f4a556fe8b9acbd4580faae92b58f0df5cce68`, 작업 branch는
`fix/issue-1006-cancellation-lifecycle`이다. 사용자가 승인한 범위는
DynamoDB와 Redisson의 nullable/result async cancellation 회귀를 안정화하고,
공개 API·ABI·production lock semantics를 바꾸지 않는 것이다. #1007 coverage
중복 집계와 #1008 Gitleaks 결정성은 별도 CI slice로 분리한다.

## 원인과 계약

`LeaderFutureBridge.CancellationRelay`는 caller future 취소를 action future에
전달한다. backend action은 `actionStarted.countDown()`을 먼저 실행한 뒤
`CompletableFuture`를 반환하므로, 신호 관찰 직후에는 relay의
`actionFuture.set(...)`이 아직 실행되지 않았을 수 있다. relay는 등록 직후
`state == CANCELLED`를 다시 확인하므로 취소 전파는 eventual contract이고,
caller `cancel(false)` 반환이나 action 시작 latch는 cleanup 완료 신호가 아니다.

Hosted Full Nightly `43110046685`에서 이 race가 DynamoDB Attempt 1과 Redisson
Attempt 1·2의 immediate `isCancelled` 단정으로 관측되었고, retry에서만
통과했다. 따라서 production 코드는 변경하지 않고, backend 회귀 테스트가
bounded Awaitility assertion으로 action 취소와 lock/permit 재획득을 각각
관찰하도록 고친다.

## 순서와 검증

1. [완료] exact head, hosted CI/Nightly 실패 로그, bridge 구현과 기존 core
   contract의 bounded assertion을 대조한다.
2. [완료] 수정 전 DynamoDB 대상 테스트와 Redisson lifecycle class를 실행해
   현재 tree의 baseline을 기록한다. 로컬 통과와 hosted retry-green은 서로
   다른 증거로 유지한다.
3. [완료] 변경 전 Type-C receipt와 mutation check를 초기화하고, 이 계획을
   기준으로 테스트 파일만 수정한다. immediate `isCancelled.shouldBeTrue()`를
   `await atMost ... untilAsserted`로 바꾸며 poll budget은 기존 모듈 관례를
   따른다. lock/permit 재획득 assertion은 유지한다.
4. [완료] DynamoDB 단일 회귀, Redisson lifecycle 전체, 공통
   `LeaderFutureBridgeTest`, detekt 및 필요한 ABI/compile 검증을 순차 실행한다.
   실패하면 원인과 scope를 다시 분리하며 production bridge 변경은 별도 승인
   없이는 시작하지 않는다.
5. [완료] 최종 diff가 두 테스트 파일과 계획/lesson 범위를 벗어나지 않는지
   확인하고, #1006에 Korean 결과와 exact test evidence를 read-back한다. PR,
   merge, issue close는 이 계획 범위에 포함하지 않는다.

## 기대 DoD

- hosted failure와 동일한 두 backend 경로가 immediate observation race 없이
  bounded assertion으로 검증된다.
- action future 취소와 lock/permit 재획득이 각각 관찰되고, fixed sleep이나
  새 dependency가 없다.
- production source/public API/ABI diff가 없고, 기존 `LeaderFutureBridge`
  cancellation semantics가 core contract와 일치한다.
- targeted tests, detekt/compile 및 diff 검사가 fresh evidence로 기록된다.

실패하면 해당 회귀 테스트부터 다시 실행한다. 테스트가 여전히 retry에
의존하면 bounded assertion보다 production lifecycle 보장이 필요한지 먼저
재검토하고, 근거 없이 retry 횟수를 늘리지 않는다.
