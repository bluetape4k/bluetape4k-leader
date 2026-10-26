# Java 직렬화 복원 경계에서 옵션 불변식 재검증하기

## 배경

이슈 #980은 `LeaderElectionOptions`와 `LeaderGroupElectionOptions`가 생성자와
`init` 블록에서 옵션을 검증하면서도 `Serializable`을 구현한다는 점에서
발생했다. Java 직렬화는 일반 생성 경로를 거치지 않으므로 변조된 payload가
`leaseTime=0`, `maxLeaders=0`처럼 유효하지 않은 상태로 복원될 수 있었다.

## 원인

생성자 검증은 새 인스턴스가 만들어질 때만 실행된다. `ObjectInputStream`은
필드를 직접 복원한 뒤 객체를 반환하므로, 불변식을 생성자에만 두면 복원된
객체가 정상적으로 보이는 타입 계약을 우회한다. 특히 Kotlin `data class`의
불변 `val`은 소스 수준에서 재할당을 막을 뿐, Java 직렬화의 필드 복원을
검증하지 않는다.

## 결정

- 두 옵션 클래스의 기존 검증식을 `validateInvariants()`로 추출해 생성자와
  복원 경계에서 동일하게 사용한다.
- private `readObject(ObjectInputStream)`에서 `defaultReadObject()` 다음에
  불변식을 재검증한다.
- 검증 실패는 원래 `IllegalArgumentException`을 cause로 보존한
  `InvalidObjectException`으로 변환한다. 기존 `serialVersionUID=1L`, 필드,
  공개 생성자와 legacy descriptor는 변경하지 않는다.
- 유효한 현재 객체의 round-trip과 각 계약 경계(`waitTime`, `leaseTime`,
  `nodeId`, `minLeaseTime`, `maxLeaders`)를 변조한 payload의 거부를 같은
  직렬화 호환성 테스트에서 고정한다.

## 검증과 결과

- 수정 전 `LeaderSerializationCompatibilityTest`에서 `leaseTime=0`과
  `maxLeaders=0` payload가 예외 없이 복원되는 RED를 재현했다.
- 수정 후 `LeaderSerializationCompatibilityTest` 7개와
  `:bluetape4k-leader-core:test` 1,085개가 모두 통과했다.
- `checkBinaryCompatibility`는 공개 ABI 변경 없이 통과했다.
- 전체 `detekt`는 기존 `MaxLineLength` 진단 때문에 실패했지만, 이번 변경으로
  추가한 `readObject` KDoc과 테스트 줄에는 신규 진단이 없다. 기존
  `useDbTime` KDoc과 다른 모듈의 진단은 Issue #979 범위로 남겼다.

## 향후 지침

`Serializable`을 추가하거나 유지하는 모델에 생성자/`init` 불변식이 있다면
항상 `readObject` 또는 `readResolve` 같은 복원 훅에서 같은 검증을 실행한다.
직렬화 호환성을 유지해야 할 때는 `serialVersionUID`와 descriptor를 보존하고,
유효한 legacy payload round-trip과 변조된 필드의 실패 타입을 함께 테스트한다.
