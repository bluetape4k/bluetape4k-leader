# Issue #739 lesson — Kotlin 기본 인자를 우회하는 Java serialization

## 맥락

`leader-spring-boot`의 `LeaderObservabilityProperties`에
`backendHealth`를, `LeaderObservabilityHealthProperties`에
`acquisitionFailureWindow`를 추가한 뒤 `0.5.0`에서 저장한 Java serialized
payload를 `1.0.0` release line에서 읽는 경계를 확인했다. `0.5.0` tag 커밋
`721a9a3808f67489d2bdb8177734325981c24977`의 실제 source로 만든 fixture를
사용했다.

## 발견

Java serialization은 Kotlin 생성자를 호출하지 않으므로 생성자 기본 인자가
적용되지 않는다. 구형 stream에는 새 필드가 없어서 현재 객체의
`backendHealth`와 `acquisitionFailureWindow`가 모두 `null`로 남았고, health
auto-configuration이 이 값을 읽는 순간 실패할 수 있었다.

## 결정

- 두 클래스의 `serialVersionUID=1L`을 유지해 기존 payload를 계속 읽는다.
- 각 클래스의 private `readResolve()`에서 누락 필드를 현재 기본값으로
  보정한다.
- outer property와 nested health property를 함께 보정한다. 한 필드만
  복구하면 같은 `0.5.0` stream의 다른 새 필드가 다시 null 경계를 만든다.
- 새 field가 있는 현재 payload의 round-trip과 실제 `0.5.0` fixture를 모두
  회귀 테스트하고, 두 health auto-configuration factory가 복원 객체를
  사용할 수 있는지 직접 확인한다.

## 결과

기존 payload를 먼저 재저장할 필요 없이 `LeaderBackendHealthAutoConfiguration`
과 `LeaderAcquisitionFailureWindowAutoConfiguration`이 각각
`LeaderBackendHealthProperties()`와 5분 window를 사용한다. 새 의존성이나
public JVM descriptor 변경은 추가하지 않았다.

## 검증

- RED: `LeaderObservabilityPropertiesSerializationTest`에서 구형 fixture를
  읽은 두 필드가 `null`이라 각각 기본값 비교가 실패했다.
- GREEN: 같은 회귀 테스트 4개 통과.
- 영향 모듈: `./gradlew :bluetape4k-leader-spring-boot:test` — 625개 통과.
- 정적/ABI: `:bluetape4k-leader-spring-boot:detekt` 통과,
  `PublicJvmAbiCompatibilityTest` 4개 통과,
  `checkBinaryCompatibility` — `artifacts=16, ignored=10, unknown=0` 및
  `Binary API compatibility gate passed`.
- 문서: 한국어 용어 감사 4개 파일, findings=0.

## 향후 지침

Kotlin `Serializable` data class에 non-null field를 추가할 때는 생성자 기본값이
구형 stream에 적용된다고 가정하지 않는다. 변경 전에 이전 release source 또는
published artifact로 실제 serialized fixture를 만들고, 다음 중 하나를 명시한다.

1. 호환성을 유지하면 `serialVersionUID`를 고정하고 `readResolve()` 또는
   명시적인 read migration으로 누락 필드를 보정한다.
2. 호환성을 중단하면 UID 변경, `InvalidClassException` 계약, migration 문서와
   fail-fast 테스트를 함께 제공한다.

어느 경로든 property 자체의 값 비교만으로 끝내지 말고, 해당 값을 소비하는
auto-configuration/read path를 복원 객체로 직접 실행해 null 경계를 닫는다.
