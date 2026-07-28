# 리더 0.3.0 출시 준비

## 맥락

0.3.0 마일스톤은 CI 통과 후 최종 릴리스-배치 PR(미리보기 백엔드 릴리스 게이트, 가상 스레드 실행기 예제 및 Redisson 감시 스타일 장기 실행 작업 예제)을 병합했습니다.

## 결정

모든 이슈 PR이 병합된 후 `develop`에서 안정적인 릴리스를 준비합니다. `baseVersion=0.3.0` 및 `snapshotVersion=`를 변경하지 않고 유지하고, 변경 로그 내용을 `Unreleased`에서 `[0.3.0]`로 승격하고, README 종속성 조각을 안정적인 `0.3.0` 좌표로 업데이트하세요.

## 결과

release-prep 분기는 태그가 절단되기 전에 공개 릴리스 정보와 사용자 대상 종속성 좌표를 기록합니다. 나머지 할당된 문제는 이후의 마이너 릴리스를 위해 백로그에 보관됩니다.

## 검증

- `gh pr checks`는 병합 전 #454, #455 및 #456에 대해 통과되었습니다.
- `./gradlew properties --no-daemon | rg '^version:|^group:'`는 `io.github.bluetape4k.leader:0.3.0`를 해결합니다.
- 태그를 지정하기 전에 `git diff --check`, `actionlint` 및 릴리스 준비 CI를 실행하세요.

## 후속 조치

0.3.0을 게시한 후 BOM과 하나 이상의 대표 모듈 POM에 대한 Maven Central HTTP 200을 검증한 후 `develop`를 다음 보조 줄로 다시 엽니다.
