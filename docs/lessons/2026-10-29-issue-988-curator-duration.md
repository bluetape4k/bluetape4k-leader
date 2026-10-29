# Java timeout 경계로 Kotlin Duration를 변환할 때 sentinel과 정밀도 보존하기

## 배경

Issue #988은 ZooKeeper Curator 연결 대기 확장이 Kotlin `Duration`을 Curator의
`int` millisecond API로 변환하면서 무한 대기, sub-millisecond, 큰 유한값을 잘못 처리한 문제다. 기존 구현은 `timeout.inWholeMilliseconds.toInt()`만 호출했다.

## 원인

Kotlin `Duration.INFINITE`의 millisecond 값은 `-1`이고, `2^31` millisecond는
`Int` 변환에서 음수로 래핑되며, 양의 sub-millisecond 값은 0으로 잘린다. Curator는 units가 지정된 `value <= 0`을 즉시 반환으로 해석하고, units가 없는 overload를 무한 대기로 해석하므로 단순한 정수 변환은 API 계약을 보존하지 못한다.

## 결정

- `Duration.INFINITE`는 Curator의 no-argument 무한 대기 overload를 호출하고 성공 의미의 `true`를 반환한다.
- 0 이하의 유한 timeout은 `0` millisecond로 정규화해 즉시 반환 계약을 보존한다.
- 양의 sub-millisecond timeout은 최소 `1` millisecond로 올린다.
- 유한 timeout이 `Int.MAX_VALUE` millisecond를 넘으면 정수 오버플로를 숨기지 않고 `IllegalArgumentException`으로 거부한다.
- 경계별 MockK 회귀 테스트로 호출 overload, millisecond 값, 반환값을 고정한다.

## 검증과 결과

- 수정 전 RED: `CuratorFrameworkDurationExtensionsTest` 6개 중 4개가 무한, 음수, sub-millisecond, 오버플로 경계에서 실패했다.
- 수정 후 대상 테스트 6개와 fresh `:bluetape4k-leader-zookeeper:test`
  125개가 통과했다.
- `:bluetape4k-leader-zookeeper:detekt`와 `git diff --check`가 통과했고, 생성된 JVM facade는 `blockUntilConnected-HG0u8IE(CuratorFramework, long): boolean`
  descriptor를 노출한다.
- 전체 `checkBinaryCompatibility`와 ZooKeeper scoped japicmp가 통과했고, ZooKeeper 항목에서는 이번 함수와 무관한 compiler-generated lambda만 분류되었다.
- `scripts/compatibility/check_binary_api_test.py` 24개는 통과했다.

## 향후 지침

Kotlin `Duration`을 Java primitive timeout으로 변환할 때는 sentinel 값, 단위 정밀도, 양·음의 경계, 대상 타입 범위를 먼저 정책으로 정하고 각 정책을 호출 인자와 반환값까지 검증하는 테스트로 고정한다. `toInt()` 단독 변환은 무한값과 오버플로를 정상 timeout처럼 숨길 수 있으므로 사용하지 않는다.
