# Spring 다중 context lease-extension 관측 범위 격리

## 맥락

lease-extension observer는 process-global 등록 모델이었고 Spring 자동 관측도 같은 dispatcher를 사용했다. 하나의 프로세스에 서로 다른 `ObservationRegistry`를 가진 application context가 둘 이상 있으면 A context의 USER/WATCHDOG event와 선택적 raw exception이 B registry에도 전달될 수 있었다. 공개 5-인자 `LeaderLeaseExtensionEvent` ABI와 명시적인 global observer 계약은 유지하면서 Spring 자동 관측만 registry별로 격리해야 했다.

## 결정

- core registration이 opaque `LeaderLeaseExtensionObservationScope` capability를 소유하고 global bucket과 scope identity bucket을 분리한다.
- producer는 event를 만들기 전에 현재 scope를 한 번 캡처하고 global bucket과 일치 bucket만 O(1)로 조회한다.
- Spring manager는 `ObservationRegistry` object identity별 canonical scope를 ref-count로 공유한다. 같은 registry를 쓰는 parent/child context는 한 scope를 공유하고 서로 다른 registry는 분리한다.
- AOP가 sync, suspend, `Mono`, `Flux`, Kotlin `Flow`, group 지원 경계에 scope를 설치한다. watchdog와 virtual/coroutine adapter는 실행 시작 시 scope를 캡처해 비동기 경계 너머로 전달한다.
- attribution이 없는 direct elector call과 Reactor operator 내부의 direct extension은 자동 observer에 귀속시키지 않는다. 명시적인 global observer는 기존처럼 event를 받는다.
- scope close는 revoke, identity bucket 제거, registration close 순서로 새 callback admission을 막는다. 이미 수락된 callback은 완료할 수 있다.
- raw exception은 기본적으로 노출하지 않으며 opt-in한 source registry에만 전달한다.

## 구현 중 발견한 경계

suspend advice에 scope가 없을 때도 `withContext(EmptyCoroutineContext)`를 추가하면 기존 exception identity와 wrapping 동작이 달라질 수 있었다. 따라서 scope가 없으면 기존 suspend lambda를 그대로 실행하고, scope가 있을 때만 context element를 결합한다.

또한 원래 `suspendBlock` 변수명을 바꾸면 Kotlin compiler-generated `WhenMappings` 클래스 이름이 달라져 공개 descriptor가 같아도 repository ABI gate가 removal로 감지했다. 원래 이름을 보존해 실행 의미와 synthetic ABI inventory를 함께 유지했다.

## 결과

- core 973 tests와 Spring 628 tests가 failures/errors/skips 없이 통과했다.
- `detekt`와 `checkBinaryCompatibility`가 통과했고 ABI inventory는 `unknown=0`이었다.
- 기존 Spring advice의 3-fork 중앙값 회귀는 throughput 1.546%, average time 0.179%로 15% 한도 안이었다.
- USER scope mismatch allocation 차이는 no-observer JMH 오차 구간 안이었고 WATCHDOG mismatch는 no-observer보다 낮았다.
- 영문·국문 README와 manual draft에 global/automatic 경계, direct-call 제외, rollout/rollback/shutdown 절차를 고정했다.

## 퓨쳐 가드

다중 application context 통합은 event payload에 registry identity를 넣어 필터링하지 말고 registration-owned capability로 admission 자체를 분리한다. 비동기 producer는 callback 시점이 아니라 실행 시작 시 scope를 캡처해야 한다. scope가 없는 기존 경로에는 불필요한 coroutine context를 추가하지 않는다. Kotlin 내부 lambda 이름도 ABI 검사 대상 synthetic class를 만들 수 있으므로 suspend advice 리팩터링 후에는 module test뿐 아니라 `checkBinaryCompatibility`를 실행한다.

## 근거

- Issue #741: https://github.com/bluetape4k/bluetape4k-leader/issues/741
- 설계: `docs/superpowers/specs/2026-08-29-issue-741-spring-observation-scope-design.md`
- 계획: `docs/superpowers/plans/2026-08-29-issue-741-spring-observation-scope-plan.md`
- 성능: `docs/benchmarks/2026-08-29-issue-741-spring-observation-scope.md`
