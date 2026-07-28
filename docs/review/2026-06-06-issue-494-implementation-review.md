# 이슈 494 구현 검토

## 범위

- 루트 README 언어 스위치 배치.
- `scripts/check-readme-language-switches.mjs`.
- `.github/workflows/readme-language.yml`.
- 향후 현지화된 README 추가를 위한 강의 항목입니다.

## 조사 결과

- P0 = 0
- P1 = 0
- P2 = 0
- P3 = 0

## 게이트

통과.

## 증거

- `node --check scripts/check-readme-language-switches.mjs`: 통과.
- `node scripts/check-readme-language-switches.mjs`: 통과(`groups=37; files=74; failures=0`).
- `actionlint .github/workflows/readme-language.yml`: 통과.
- `git diff --check`: 통과.

## 메모

스크립트는 모든 현지화된 README 형제 세트에 대해 표시되는 로케일 순서를 적용하고 한국어가 순서 전환에서 두 번째 항목으로 남아 있지 않는 한 향후 일본어/중국어 파일을 차단합니다.
