# #994 구현 전 위험 예측

## 범위와 기준

이 문서는 승인된 [구현 계획](../plans/2026-10-30-issue-994-inline-backend-facades-plan.md)의 Step 3-P 예측이다. 대상은 `ad4110a92afb0c3f40bc0e84e7e9da1e6b61c97f` 기준 여섯 backend의 facade 36개이며, `etcdClientOf`와 나머지 비대상 선언은 유지한다. 계획이 고정한 JVM ABI 기준은 `6f6697b8a10290778ab8e16a5a6441b424f3e6e3`, 과거 DynamoDB consumer 기준은 `a6e17f71cdfde18b6d7e8575c7db26385902e9b4`다.

## 상위 위험과 조기 신호

| 구현 작업 | 예측 위험 | 조기 신호 | 예방 및 판정 | 실패 때 되돌림·재실행 |
|---|---|---|---|---|
| 작업 2–5 | `inline`과 `crossinline` 제거 중 callback 실행 횟수·순서, 반환값, 예외 또는 취소 정리가 달라질 수 있다. | characterization test에서 callback 횟수나 결과가 달라지거나, 경합 후 lock slot을 재획득하지 못한다. | body 변경을 최소화하고 기존 위임·dispatch 순서를 유지한다. blocking, async, suspend, virtual-thread 경로에서 관련 테스트를 통과시킨다. | 영향 선언의 기존 body와 modifier를 복원하고 해당 backend 테스트부터 다시 실행한 뒤 여섯 모듈 검증을 반복한다. |
| 작업 1–4 | DynamoDB hidden shim의 owner, descriptor, `$default`, annotation 또는 source-visible 연결이 달라질 수 있다. | 68개 현재 callsite와 과거 4개 callsite의 `javap` 결과가 기준 manifest와 다르거나 `checkBinaryCompatibility`가 실패한다. | 고정 기준 SHA에서 명시/default 인자 consumer를 컴파일한다. modifier 외에 `@JvmName`, `@Deprecated(HIDDEN)`, 파라미터와 기본값을 바꾸지 않고 method descriptor/access/annotation을 비교한다. | 변경한 shim 선언을 승인된 형태로 복원하고 ABI·consumer 검증기를 다시 실행한다. manifest 불일치는 verifier를 먼저 점검하며 기준을 임의로 갱신하지 않는다. |
| 작업 2–4 | 의도한 facade 호출 경로를 검증기가 놓치거나, `etcdClientOf`의 non-local return source 계약을 잃을 수 있다. | 34개 facade의 explicit/default callsite가 모두 직접 facade를 호출하지 않거나, block-body non-local return fixture가 컴파일되지 않는다. | checksum 고정된 68행 callsite manifest와 verifier self-test를 사용한다. `etcdClientOf`는 inline으로 두고 별도 inline `etcdClient`도 불변인지 확인한다. | 검증기 실패 시 source 변경을 진행하지 않고 manifest/parser를 수정해 self-test를 재실행한다. factory 계약이 깨지면 선언을 inline으로 복원하고 consumer fixture를 다시 컴파일한다. |
| 작업 5 | Docker/Colima 환경 실패를 제품 결함 또는 성공으로 잘못 분류할 수 있다. | Testcontainers 연결·socket 오류, 테스트 시작 전 종료, retry 뒤에만 green인 첫 실패, 신규 skip이 나타난다. | backend별 로그와 JUnit XML을 확인한다. 컨테이너 테스트 전 `colima status`, `docker context show`, `docker info`를 확인하고 실제 실행·skip 수를 기록한다. | 환경 오류와 assertion 실패를 분리한다. 환경 확인 후 실패한 모듈을 순차 재실행하며, 미실행 또는 skip 검증은 PENDING으로 둔다. |

## 구현 중단 기준

- 37개 선언의 inventory, 68개 현재 callsite, 네 개 역사적 DynamoDB callsite의 기준 수가 맞지 않으면 source 변경 전에 멈춘다.
- 예상하지 않은 descriptor, annotation, callback 순서 또는 async/suspend cleanup 차이가 생기면 해당 source 변경을 복원하고 원인을 확인한다.
- 컨테이너 테스트가 환경 오류로 시작하지 못하면 제품 코드 결론을 내리지 않고 환경 복구 또는 별도 실행 증거를 기다린다.
- 이 변경은 inline 구현을 facade 호출로 옮기는 API 경계 조정이다. 별도 benchmark 목표가 승인되지 않았으므로 성능 향상·저하 수치를 주장하지 않는다.

## Step 3-P 판정

**PASS — 구현 진행 가능.** 위 네 위험은 각각 계획 작업 1–5의 baseline, 회귀 테스트, ABI/consumer 검사, backend 환경 점검 및 되돌림 지점에 연결했다. 구현 범위와 승인된 설계는 바꾸지 않는다.
