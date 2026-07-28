# 이슈 489 의미론적 경로 색상

## 맥락

리더 README 시퀀스 다이어그램은 대부분 중립 회색 커넥터를 사용했기 때문에 잠금 획득, 건너뛰기, 해제, 재시도 및 재획득 경로를 구별하기 어려웠습니다.

## 결정

생성된 README 다이어그램 전체에서 안정적인 의미 체계 커넥터 팔레트를 사용합니다(중성 회색, 리더/success 녹색, 건너뛰기/failure 분홍색, 경합/릴리스 황색, 재획득/다음 실행 보라색). 각 의미 경로의 톤을 `data-route-tone`로 저장하고 Graphviz 증거 스크립트에서 획과 화살표 표시 일관성을 검증합니다.

## 결과

- 기존 시퀀스 SVG 자산의 경로 순서 기반 의미 색상 지정을 위해 `scripts/apply-lock-state-line-colors.mjs`를 추가했습니다.
- 시맨틱 컬러 게이트로 `scripts/regenerate-readme-diagram-graphviz-evidence.mjs`를 확장했습니다.
- 렌더링된 검토 후 추가된 `scripts/compact-sequence-call-spacing.mjs`는 일반 시퀀스 다이어그램에서 과도한 함수 호출 간격을 보여주었습니다.
- 텍스트 오버플로를 허용하는 대신 ZooKeeper 스케줄러 시퀀스 캔버스/헤더 너비를 확대했습니다.

## 검증

- `node scripts/generate-zookeeper-scheduler-readme-diagrams.mjs`
- `node scripts/apply-lock-state-line-colors.mjs`
- `node scripts/compact-sequence-call-spacing.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- 모든 README 다이어그램 SVG용 `xmllint --noout`
- 75개 README 파일에 대한 README 이미지 링크 검증
- `git diff --check`

## 향후 지침

라인 라우팅이나 라벨 맞춤이 빡빡한 경우 먼저 다이어그램 영역을 확대하세요. 일치하는 화살촉, 레이블 색상 및 오래된 경로 스타일 지정에 failure한 증거 게이트 없이 의미 체계 색상 변경을 제공하지 마세요.

시퀀스 다이어그램의 경우 y 좌표를 균일하게 크기 조정하는 것이 아니라 전체 메시지 그룹을 이동하여 과도한 수직 간격을 줄입니다. 균일한 크기 조정으로 인해 고정 높이 레이블 상자가 호출 또는 반환 라인과 충돌하게 됩니다.
