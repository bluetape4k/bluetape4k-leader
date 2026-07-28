# README 다이어그램 Graphviz 증거

## 맥락

README 다이어그램 SVG/PNG 자산이 인접한 Graphviz 증거 파일 없이 존재했습니다. 이제 다이어그램 기술은 Graphviz `.dot`, `.plain` 및 스케치 SVG를 노드 및 커넥터 README 다이어그램에 대한 필수 경로/레이아웃 증거로 처리합니다.

## 결정

최종 SVG 노드/경로를 추출하고, 각 README 다이어그램 옆에 일치하는 Graphviz 증거를 작성하고, 해당 증거를 가리키는 SVG 메타데이터를 삽입하고, 명시적인 글꼴 검색으로 PNG를 렌더링하는 저장소 스크립트를 추가합니다. 또한 스크립트는 일반 카드 텍스트 블록의 수직 중앙 정렬을 검증하여 미리보기 전에 상단 또는 하단 바이어스 카드 레이블이 failure하도록 합니다. 후속 검토에서는 Graphviz 증거가 최종 좌표를 추적했지만 최종 상자가 겹침을 피했거나 시퀀스 다이어그램이 필수 외부/내부 간격 규칙을 따랐다는 것을 입증하지 않았으므로 해당 검사가 동일한 스크립트로 승격되었습니다.

## 결과

모든 최종 `docs/images/readme-diagrams/*.svg` 자산은 이제 일치하는 `.dot`, `.plain`, `*-graphviz.svg` 및 `*-graphviz.png` 증거를 갖습니다. 모든 SVG 아티팩트에는 PNG 대응물이 있습니다. 필수 글꼴은 로컬 글꼴 디렉터리에서 검증되고 렌더러 폴백을 허용하는 대신 Fontconfig를 통해 렌더링 경로로 전달됩니다.

`bluetape4k-leader-architecture-01`는 공간이 재조정되어 `leader-core`는 왼쪽에 유지되고 Exposed JDBC/R2DBC 모듈은 상자 중첩 없이 오른쪽에 유지됩니다. 필요한 경우 시퀀스 다이어그램이 넓어져서 참가자 헤더의 외부 여백이 넉넉해졌고, 메시지 레이블은 이제 화살표 가까이에 배치되었습니다.

## 검증

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- `xmllint --noout docs/images/readme-diagrams/*.svg`
- `git diff --check`
- README 이미지 링크/증거 검사기: `missing=0`
- 노드 오버랩 게이트: `bluetape4k-leader-architecture-01`용 `overlaps=0`
- 시퀀스 간격 게이트: 참가자 여백은 최소 60px이고 라벨과 화살표 사이의 간격은 최대 10px입니다. 현재 생성된 시퀀스 세트는 `sequence_spacing_failures=0`를 보고합니다.
- 시각적 밀착 시트: `.omx/artifacts/leader-readme-diagram-contact-sheet.png`
- 시퀀스 시각적 밀착 시트: `.omx/artifacts/sequence-final-contact-sheet.png`
- SVG/PNG 쌍 검증: `svg=70`, `png=70`, `missing_svg_png_pairs=0`

## 향후 지침

README 다이어그램 재생성을 위해 최종 SVG/PNG 존재에서 멈추지 마십시오. 완료를 보고하기 전에 생성된 모든 SVG, SVG 메타데이터, 의도된 글꼴 해상도, 텍스트 수직 정렬 검사 및 시각적 미리보기 증거에 대해 일치하는 PNG 미리 보기와 일치하는 Graphviz 증거 파일이 필요합니다. 최종 SVG 좌표가 고정된 Graphviz 위치로 사용되는 경우에도 최종 노드 중첩을 직접 검증하세요. Graphviz 증거와 일치한다고 해서 최종 시각적 간격이 허용된다는 사실이 입증되지는 않습니다. 시퀀스 다이어그램의 경우 미리보기 전에 외부 참가자/머리글 여백과 작은 레이블-화살표 간격을 모두 검증하세요.
