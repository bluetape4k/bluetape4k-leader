# README 다이어그램 인포그래픽

## 맥락

README 파일은 아키텍처, 클래스, 시퀀스, ERD 및 기타 다이어그램에 Mermaid 코드 블록을 사용했습니다. 작업 공간 전체의 시각적 방향은 재사용을 위해 유지되는 SVG 소스 자산과 함께 검토된 파스텔 인포그래픽 PNG로 변경되었습니다.

## 결정

README 인어 블록을 생성된 PNG 이미지 링크로 바꾸고 일치하는 SVG 소스를 PNG 파일 옆에 저장하세요. 영어 전용 다이어그램 텍스트, 대형 라벨용 Architects Daughter, 세부 텍스트용 Comic Mono, 아키텍처, 클래스, 시퀀스 및 ERD 다이어그램용 다이어그램별 레이아웃을 사용하세요.

## 결과

bluetape4k.github.io/docs/readme-diagram-samples의 공유 2026-05-19 스타일 가이드를 사용하여 README 다이어그램을 렌더링했습니다. 루트 README 자산은 존재하는 경우 repo-local 자산 배치 규칙을 따릅니다.

## 검증

rsvg-convert를 사용하여 PNG/SVG 자산을 생성하고 저장소 간 변환 과정에서 README 링크를 검증했습니다.

## 향후 지침

편집을 위해 SVG 소스가 포함된 PNG로 README 다이어그램을 유지하세요. 시각적 일관성이 중요한 경우 원시 인어 또는 단순한 인어 테마 다시 칠하기로 돌아가지 마십시오.
