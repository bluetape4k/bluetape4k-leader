# 공개 KDoc 줄 길이 회귀를 정적 분석 단계에서 차단하기

## 맥락

Issue #979는 공개 API KDoc을 확장한 뒤 `MaxLineLength` 진단이 여러 모듈의
Detekt와 Nightly 검증을 차단한 사례다. 기능 동작은 바뀌지 않았지만, 현재
`develop`에서 전체 `detekt`를 실행하면 10개 모듈에 걸쳐 신규 진단 39건이
발생했다.

## 원인

`219f0402`의 공개 API KDoc 변경에서 긴 설명을 한 줄로 추가했다. 모듈별
기존 Detekt baseline은 이전 진단을 보관하고 있었지만, 해당 커밋의 39건은
baseline에 포함되지 않은 신규 `MaxLineLength` 진단이었다. 따라서 테스트
실행 전에 정적 분석 단계가 실패했고, 코드 동작이나 Testcontainers 환경의
문제가 아니었다.

## 결정

- 진단이 발생한 KDoc 39줄만 단어 경계에서 줄바꿈하고 설명의 의미와
  `@property`, `@param`, `@return` 표기를 보존한다.
- Detekt baseline을 갱신해 신규 진단을 숨기지 않으며, 기능 코드와 기존
  baseline은 변경하지 않는다.
- 공개 KDoc 변경 뒤 PR 전에 전체 `./gradlew detekt --continue`를 실행해
  모든 모듈의 신규 `MaxLineLength` 진단을 확인한다.

## 검증과 결과

- 수정 전 전체 Detekt에서 exit 1과 `MaxLineLength` 39건을 재현했다.
- 수정 후 `./gradlew detekt --continue --max-workers=1 --console=plain
  --no-configuration-cache --no-daemon`이 exit 0, `BUILD SUCCESSFUL`로
  완료됐고 `MaxLineLength` 진단은 0건이었다.
- 변경된 모든 KDoc 줄은 120자 이하이며 `git diff --check`가 통과했다.

## 향후 지침

공개 API KDoc을 추가하거나 확장할 때는 한 줄 길이를 120자 미만으로 유지하고,
문서 변경만 포함된 커밋도 전체 Detekt를 PR 전 필수 검증으로 실행한다. 기존
baseline에 신규 진단을 추가하는 방식은 Nightly 차단 원인을 숨기므로 사용하지
않는다.
