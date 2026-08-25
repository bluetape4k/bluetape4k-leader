# Public bridge ABI classifier를 fail-closed로 유지하기

## 맥락

`checkBinaryCompatibility`는 japicmp가 보고한 public JVM incompatibility를
분류한 뒤, 분류되지 않은 항목이 있으면 release gate를 실패시킨다. 기존
분류기는 `BRIDGE` 문자열이 포함된 incompatible member를 class와 descriptor
확인 없이 모두 `JVM bridge method`로 무시했다. JVM compiler bridge도 이미
배포된 consumer가 직접 링크할 수 있으므로 이 규칙은 `NoSuchMethodError`를
release gate에서 숨길 수 있다.

## 결정

- public bridge member에는 blanket ignore를 적용하지 않는다.
- 현재 허용하는 synthetic accessor는 `SYNTHETIC`과 `access$`를 모두 포함한
  member로 제한한다.
- 향후 의도된 compiler-generated bridge를 허용해야 한다면 class와 JVM
  descriptor를 함께 고정한 exact allowlist 또는 실제 published consumer
  linkage fixture를 먼저 추가한다.

## 검증

- RED: public `BRIDGE` 제거 fixture가 기존 `JVM bridge method` 분류 때문에
  실패함을 확인했다.
- GREEN: `python3 -m unittest scripts.compatibility.check_binary_api_test -v`
  — 7개 통과. generic override가 생성한 실제 `ACC_BRIDGE`를 `javap`로
  확인하고, 기존 consumer의 `Object` descriptor linkage가 bridge 없는 새
  클래스에서 `NoSuchMethodException`으로 실패하는 fixture도 통과했다.
- 정적 검증: `py_compile` 및 `git diff --check` 통과.
- 현재 release 경로에 맞춘
  `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0 ./gradlew checkBinaryCompatibility`
  는 `unknown=5`로 실패했다. synthetic accessor 7개는 분류됐고, 남은
  incompatibility는 `leader-spring-boot`의 기존 공개 API 제거·생성자·직렬화
  진단이다. 따라서 이 실행은 classifier 수정의 GREEN 증거가 아니라,
  fail-closed 전체 gate가 동작한다는 증거로 기록한다.
- Issue가 작성될 당시의 `0.6.0` 릴리스선 커밋 `af3b4369`를 임시 detached
  worktree에서 재현했다. 동일한 수정과 6개 회귀 테스트 뒤
  `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=0.6.0 ./gradlew
  checkBinaryCompatibility`가 `artifacts=16, ignored=4, unknown=0` 및
  `BUILD SUCCESSFUL`로 통과했다.

## 다음 변경자를 위한 주의

ABI gate를 통과시키기 위해 public bridge를 다시 blanket ignore로 되돌리지
않는다. 현재 JDK generic override fixture는 bridge의 직접 linkage 위험을
고정하지만, 실제 `0.5.0 consumer → 0.6.0 published jar` 검증은 해당
consumer와 아티팩트 provenance를 확보한 후 별도 작업으로 닫는다. 현재
확보한 0.5.0 → 0.6.0 gate 결과는 classifier가 unclassified public
incompatibility를 숨기지 않는다는 release-path 증거다.
