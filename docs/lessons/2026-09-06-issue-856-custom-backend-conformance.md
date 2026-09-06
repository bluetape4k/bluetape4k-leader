# Issue #856 custom backend conformance 교훈

## 배경

외부 custom strategic backend는 production interface를 구현할 수 있었지만, 후보 등록부터
경합, TTL 만료, 결과 기록까지 같은 의미를 지키는지 재사용 가능한 방식으로 검증하기
어려웠다. 기존 lock/lease fixture만으로는 strategic candidate registry의 계약을 증명할 수
없었다.

## 결정

production API를 넓히지 않고 `leader-core`의 published testFixtures에 provider-injectable
abstract fixture를 추가했다. fixture는 blocking/suspend와 single/group 네 mode를 같은
assertion으로 검증하고, 외부 backend가 client, namespace, actual-expiry 확인과 cleanup을
소유하게 했다.

## 예상 밖의 발견

첫 설계의 강제 삭제 hook은 테스트를 쉽게 만들지만 TTL 자체를 우회했다. 또한 Redis의
`remainTimeToLive == 0`은 곧바로 key 부재를 뜻하지 않았다. #884 전체 빌드에서 Redisson
expiry race가 드러났고, actual key 부재를 기다리도록 바꾸자 fixture와 기존 regression
test의 만료 의미가 일치했다.

concurrency test도 단순히 “호출이 끝났다”는 사실만으로 충분하지 않았다. non-atomic
read-modify-write 변형을 넣었을 때 기대 200 대비 55/63만 기록되어, 최종 카운터 assertion이
실제로 lost update를 잡는다는 것을 확인했다.

## 결과

- custom provider가 네 strategic mode의 lifecycle과 winner 의미를 한 번에 검증한다.
- actual TTL expiry 뒤 refresh가 후보를 부활시키지 못한다.
- register, refresh, updateResult, unregister의 경합 결과가 명시적으로 검증된다.
- suspend cancellation은 실패 결과로 오염되지 않고 재전파된다.
- 영문·한글 README가 strategic와 lock 기반 async/virtual-thread capability 경계를 구분한다.

## 검증과 검토 누락 방지

targeted GREEN만으로 끝내지 않고 compile RED, 잘못된 assertion RED, non-atomic mutation RED,
core 전체, Redisson module, detekt, ABI, publication metadata를 확인했다. 인라인 리뷰에서
독립 검토 provenance를 주장하지 않았고, PR exact-head CI는 별도 gate로 남겼다.

## 앞으로의 원칙

1. expiry conformance는 관리 API 삭제가 아니라 backend가 관찰하는 실제 부재나 제어된
   fake clock의 자연 만료를 증명한다.
2. concurrency conformance는 최종 허용 상태뿐 아니라 정확한 result counter처럼 lost
   update를 구별하는 값을 확인한다.
3. testFixtures public API도 consumer source contract이므로 최소 surface와 명확한 capability
   경계를 유지한다.
4. Redis TTL 숫자는 key lifecycle의 대용물이 아니다. 후속 mutation의 안전성이 중요하면
   실제 key 부재를 확인한다.
5. 새 backend는 fixture 통과를 기능 계약의 출발점으로 사용하되 성능, retry, provisioning,
   장애 복구는 별도 통합 테스트와 운영 검증으로 증명한다.

## Writer DoD

- SPW-01 PASS: custom backend 구현자와 후속 reviewer를 독자로 삼았다.
- SPW-02 PASS: 배경, 결정, 예상 밖의 발견, 결과, 검증, future guard를 포함했다.
- SPW-03 PASS: Korean naturalness KO-01~KO-06을 확인하고 code token을 보존했다.
- SPW-04 PASS: 테스트 수치와 Redisson 조건을 실행 로그와 diff에 대조했다.
- SPW-05 PASS: 과장된 보증 없이 fixture의 미보증 범위와 PR gate를 명시했다.
