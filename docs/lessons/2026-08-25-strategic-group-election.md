# #463 전략적 그룹 선출 구현 교훈

## 결정

- 기존 `LeaderGroupElectionOptions`를 strategic group API에 그대로 재사용하지 않고 `maxLeaders: Int`를 직접 노출했다. strategic API는 후보 기준 top-N이고, 기존 group elector는 전역 distributed slot claim이기 때문이다.
- custom strategy 결과는 action 전에 core의 `electValidated` 한 곳에서 검증한다. backend별 검증 복제는 계약 drift를 만들 수 있어 제거했다.
- Lettuce와 Redisson은 저장 자료구조가 다르므로 후보 group namespace를 `lettuce:v1`와 `redisson:v1`로 분리했다.
- 후보별 TTL과 index 수명은 분리했다. Lettuce index set을 persistent로 유지하고 candidate key만 만료시키며, Redisson 갱신은 잔여 TTL을 보존한다.
- 점수 입력은 `bluetape4k-core`의 `Double.requireFinite`를 재사용해 NaN/무한을 fail-closed로 처리한다.

## 검증 방식

- 먼저 실패하는 계약 테스트를 작성한 뒤 core, Local, Lettuce, Redisson blocking/coroutine 구현을 순서대로 통과시켰다.
- mixed-TTL, backend namespace 충돌, custom winner 검증, 결과 갱신, cancellation 경계를 회귀 테스트로 고정했다.
- 최종 head에서 core 49, Lettuce 51, Redisson 41, 기존 Local group 23 테스트와 detekt/build/ABI를 다시 실행했다.

## 후속 보강

- Local 후보 snapshot의 약한 일관성과 Redis read-modify-write 갱신 경계는 별도 설계 이슈로 분리한다.
- Redis coroutine adapter의 실제 cancellation 및 SUCCESS/FAILURE 갱신 회귀를 Testcontainers 테스트에 추가한다.
- public `StrategicGroupElectionResult` 필드 확장은 constructor/copy ABI를 먼저 검토한다.
