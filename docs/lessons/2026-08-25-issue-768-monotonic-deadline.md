# 배운 교훈 - 공통 monotonic deadline과 ticker wrap-around

## 맥락

Issue #768에서 Lettuce, MongoDB, DynamoDB helper가 `System.nanoTime()`의 현재
값에 timeout을 더한 절대 deadline을 저장하고 있었습니다. `Long.MAX_VALUE`에
가까운 ticker origin에서는 overflow를 피하려고 deadline을 포화시키면서 실제
100ms wait budget이 10ns로 축소되었습니다. Exposed JDBC/R2DBC는 이미 경과
시간 차이를 사용했지만 helper 구현이 중복되어 backend 간 계약이 분리되어
있었습니다.

## 결정

leader-core에 시작 시각, timeout, ticker를 보관하는 공통 `MonotonicDeadline`을
두고 모든 대상 backend helper가 이를 위임하도록 했습니다. 남은 예산은
`(ticker() - startNanos).coerceAtLeast(0L)` 경과 차이로 계산하며, 절대 deadline을
만들지 않습니다. Lettuce의 park/delay 상한, MongoDB/DynamoDB의 양수 delay
검증, Exposed의 sleep 단위와 같은 기존 backend 표면은 wrapper에서 유지해
호출자와 JVM ABI를 보존했습니다.

## 결과

zero/negative wait는 즉시 만료되고 sub-millisecond 예산은 마지막 1ms 창으로
보존됩니다. arbitrary ticker origin과 Long wrap-around에서도 전체 wait budget이
유지되며, 단일·그룹 lock의 skip/null 및 cancellation 계약은 변경하지 않습니다.

## 검증

- RED: Lettuce와 MongoDB near-wrap 테스트가 기존 10ns/1ms 결과를 재현하며
  `100ms` 기대에 실패했습니다.
- GREEN: 공통 수학 fixture를 Lettuce/MongoDB/DynamoDB에 연결하고 Lettuce 6개,
  MongoDB/DynamoDB 각 4개 테스트가 통과했습니다.
- 전체 모듈: Core 839개, MongoDB 132개, DynamoDB 117개, Exposed JDBC 301개,
  Exposed R2DBC 320개, Lettuce 273개 테스트가 통과했습니다.
- Lettuce 전체 suite는 첫 실행에서 기존 Redis timing 테스트 1개가 실패했으나,
  동일 테스트 격리 재실행과 최종 전체 suite 재실행에서 273개가 모두 통과했습니다.
  hosted CI에서도 exact-head 결과를 별도로 확인해야 합니다.
- `detekt`와 `git diff --check`는 통과했습니다.
- ABI gate는 `ABI_BASE_VERSION=0.4.0 ABI_CURRENT_VERSION=1.0.0`으로 실행했고,
  현재 변경과 무관한 `leader-spring-boot`의 기존 미분류 incompatibility 1건으로
  종료되었습니다.

## 향후 지침

모든 blocking/suspend retry와 polling loop는 wall-clock 또는
`start + timeout` 절대 deadline을 직접 계산하지 말고 공통 elapsed-subtraction
helper를 사용하세요. 새 backend를 추가할 때는 zero/negative,
sub-millisecond, normal elapsed, arbitrary-origin, wrap-around 경계를 공통
fixture로 고정하고, cancellation은 실제 lock 경합 테스트에서 결과 계약까지
확인하세요.
