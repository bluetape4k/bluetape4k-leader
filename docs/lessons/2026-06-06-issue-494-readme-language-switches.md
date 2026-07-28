# 2026-06-06 - 문제 494 README 언어 스위치

## 맥락

Issue #494는 PR #493이 현재 영어/한국어 링크를 정규화한 후 저장소 전체 README 언어 전환 규칙을 추적합니다.

## 결정

표시되는 언어 순서를 `English | 한국어`로 안정적으로 유지한 다음 한국어 뒤에 향후 일본어 및 중국어 항목을 추가합니다. 수동 검토에 의존하는 대신 경량 Node 스크립트를 사용하여 규칙을 시행하세요.

## 결과

루트 README 파일은 이제 H1 제목 바로 아래에 언어 스위치를 배치하고, 새로운 `README Language` 워크플로는 문서 전용 PR에서 무거운 CI를 강제하지 않고 README 또는 유효성 검사 변경을 위한 스크립트를 실행합니다.

## 검증

- `node --check scripts/check-readme-language-switches.mjs`
- `node scripts/check-readme-language-switches.mjs`
- `actionlint .github/workflows/readme-language.yml`
- `git diff --check`

## 향후 지침

`README.ja.md` 또는 `README.zh.md`를 추가할 때 형제 파일을 먼저 추가하고 `scripts/check-readme-language-switches.mjs`가 모든 지역화된 README가 동일한 순서의 스위치를 사용하도록 강제합니다.
