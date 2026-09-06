# Hazelcast wait budget을 공통 monotonic deadline으로 계산한다

## 맥락

Hazelcast blocking/suspend lock의 bounded retry는 `System.currentTimeMillis()`로
절대 deadline을 만들었다. NTP 보정이나 운영 중 wall clock 변경이 발생하면 실제
경과 시간과 남은 `waitTime`이 달라질 수 있었다. 반면 lease 만료 시각은 외부에
표현하는 epoch 값이므로 wall clock을 계속 사용해야 한다.

## 결정 또는 발견

두 `tryLock` 경로는 `leader-core`의 `MonotonicDeadline`으로 남은 재시도 예산을
계산한다. 공개 `tryLock(waitTime, leaseTime)` 시그니처는 그대로 두고,
`@JvmSynthetic internal` overload로 ticker와 sleep/delay를 주입해 시간 이동과
취소를 실제 루프에서 결정적으로 검증한다.

재시도 간격은 기존과 같이 최대 50ms이며 마지막 대기는 남은 예산으로 제한한다.
0 또는 음수 wait도 기존처럼 첫 backend 시도 한 번은 수행하고 즉시 `false`를
반환한다. blocking interruption과 coroutine cancellation은 잡지 않고 호출자에게
전파한다.

## 결과

- wall clock 전진·후퇴가 blocking/suspend wait budget을 늘리거나 줄이지 않는다.
- blocking과 suspend가 같은 공통 deadline 수학 계약을 사용한다.
- lock contention의 공개 결과인 `false`와 기존 lease 만료 시각 의미는 바뀌지 않는다.
- Hazelcast가 backend 공통 `AbstractMonotonicDeadlineMathContractTest` inventory에 포함된다.

## 검증

- RED: 새 loop seam이 없는 기존 구현에서 `compileTestKotlin`이 `ticker`, `sleep`,
  `delayMillis` 인자를 찾지 못해 실패했다.
- GREEN: `HazelcastMonotonicDeadlineTest`와 `HazelcastMonotonicWaitTest`의 10개 테스트가 통과했다.
- 첫 GREEN 시도는 테스트 전에 Freefair plugin classpath의 `TaskUtils` 로딩 실패로
  중단됐다. configuration cache를 끈 새 Gradle process에서 재실행해 코드와 분리했다.
- 전체 Hazelcast module test 131개, detekt, binary compatibility는 통과했다.
  exact-head CI 결과는 PR 검증 단계에서 별도로 기록한다.

## 향후 지침

bounded local retry에는 epoch timestamp 덧셈이 아니라 elapsed subtraction 기반
`MonotonicDeadline`을 사용한다. 외부 저장소에 기록하거나 사용자에게 반환하는 lease
만료 시각과 로컬 wait budget을 같은 clock 계약으로 취급하지 않는다. 시간 기반 loop는
공개 API를 늘리지 않는 test seam으로 ticker와 대기를 주입해 clock 이동, 경계값,
interruption/cancellation을 함께 검증한다.
