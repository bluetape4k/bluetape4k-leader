# Issue #731 — Kotlin private nested class의 JVM factory-only 경계

## 맥락

OBS-03의 `LeaderAuditExportEvent.History`와 `Lifecycle`은 sanitizer를 거친
immutable event payload만 외부 exporter에 전달해야 합니다. 기존 소스는 nested
`Snapshot` class를 `private`로 선언했지만, JVM bytecode에는 raw field를 받는
public constructor가 남아 있었습니다. 따라서 Kotlin 소스의 가시성만 확인하면
factory-only construction 계약을 놓칠 수 있습니다.

## 원인

Kotlin의 private nested class와 primary constructor의 가시성은 별개입니다.
`private class Snapshot(...)`은 nested type을 숨기지만 primary constructor를
private로 만들지 않습니다. 여기에 constructor를 명시적으로 private로 바꾸면
Kotlin compiler가 `DefaultConstructorMarker` synthetic bridge를 추가하므로,
bridge descriptor에도 raw field type이 남지 않는지 별도로 확인해야 합니다.

## 결정

- `History.Snapshot`과 `Lifecycle.Snapshot`의 primary constructor를 명시적으로
  `private`로 고정합니다.
- constructor에는 raw `String`, `Map`, `Instant`, `LeaderLease`, history
  record를 전달하지 않고, outer factory가 sanitizer를 적용한 뒤 만드는 private
  `SnapshotData` interface만 전달합니다.
- Kotlin 테스트는 유일한 non-synthetic constructor가 private인지 확인하고,
  public/synthetic constructor descriptor에 금지된 raw type이 없는지 검사합니다.
- Java same-package fixture는 동일한 규칙을 reflection으로 재검증합니다.

## 결과

`History`와 `Lifecycle`은 기존 public factory와 sanitized immutable event
계약을 유지하면서, nested event payload의 non-synthetic constructor를 private로
제한합니다. Kotlin이 생성하는 synthetic bridge에는 `SnapshotData`와
`DefaultConstructorMarker`만 남아 raw event field descriptor가 외부 surface로
노출되지 않습니다.

## 검증

- RED: 새 nested constructor visibility 테스트가 수정 전 `0 passing, 1 failing`으로
  실패했고, `Expected <false> to be <true>, but was not`을 확인했습니다.
- GREEN: `LeaderAuditExportEventTest`와
  `LeaderAuditExportBoundaryContractTest`가 `19 passing`으로 통과했습니다.
- `javap -p -s`에서 두 private constructor descriptor가
  `SnapshotData`만 받고, public synthetic bridge에는 raw field type이 없음을
  확인했습니다.
- `./gradlew :bluetape4k-leader-core:test --no-daemon --rerun-tasks`: `806 passing`.
- `./gradlew :bluetape4k-leader-core:detekt --no-daemon --rerun-tasks`: 통과.
- `git diff --check`: 통과.

## 놀라움과 복구

처음에는 constructor에 `private`만 추가하면 충분하다고 판단했지만, Kotlin
compiler가 outer factory에서 private constructor를 호출할 수 없다고 거부했고,
companion factory를 추가한 뒤에는 `DefaultConstructorMarker` bridge에 raw field
descriptor가 남았습니다. constructor 입력을 opaque `SnapshotData`로 좁히고
Kotlin·Java descriptor 검사를 함께 추가해 두 경계를 분리했습니다.

## 향후 지침

Kotlin에서 factory-only payload를 만들 때는 nested class의 `private` 선언만
검사하지 말고 primary constructor visibility와 compiler synthetic bridge를
함께 확인합니다. public API 또는 security boundary에 raw payload가 없어야 하면
`javap`와 same-package Java reflection fixture를 유지하고, `String`·`Map`·
`Instant`·token·`LeaderLease` 같은 내부 입력 타입이 constructor descriptor에
다시 나타나는지 회귀 테스트로 고정합니다.
