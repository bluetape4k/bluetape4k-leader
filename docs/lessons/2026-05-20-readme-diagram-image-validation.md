# README 다이어그램 이미지 검증

## 맥락

bluetape4k-leader의 README 다이어그램은 공유 파스텔 인포그래픽 렌더러로 새로 고쳐졌습니다. 이 작업은 현재 Mermaid 블록과 git 기록에서 복구된 기존 README 다이어그램 이미지 링크를 다룹니다.

## 결정

README 관련 아티팩트로 PNG를 사용하고 재사용을 위해 PNG 파일 옆에 SVG 소스를 유지합니다. 다이어그램 레이블은 영어로만 제공됩니다. `Diagram`, `Architecture` 및 `Sequence Diagram`와 같은 일반 제목은 모듈별 영어 제목으로 대체됩니다. 영어가 아닌 텍스트가 손실된 시퀀스 라벨은 의미 없는 일반 라벨 대신 참여 구성요소로 대체됩니다.

## 결과

- 62개의 렌더링된 아티팩트
- PNG 파일 31개
- 31개의 SVG 소스 파일
- 누락된 README 이미지 링크 없음
- README 파일에 로컬 SVG 이미지가 포함되지 않습니다.
- 남은 인어 코드 블록 없음
- 형상 검증 후보 없음

## 검증

- `node /Users/debop/work/bluetape4k/.omx/scripts/refine-readme-diagrams.mjs .`
- README 이미지 링크 및 인어 잔여물 검사기
- PNG/SVG 모양 검사기
- 시각적 밀착인화지 검토: `/tmp/bluetape4k-leader-diagram-review-samples.png`
- `git diff --check`

## 향후 지침

이전에 대체된 블록에 대한 Git 기록을 포함하여 가능한 경우 원본 Mermaid 소스에서 다시 생성합니다. 이미지 크기를 콘텐츠 중심으로 유지하고, 가짜 필러 노드를 피하고, SVG 소스를 보존하고, 게시하기 전에 샘플 시트를 검사하세요.
