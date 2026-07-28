# Issue #490 계층형 아키텍처 다이어그램 강의

## 맥락

Issue #490은 #489 잠금 상태 경로 색상 작업 후 README 아키텍처 다이어그램을 새로 고쳤습니다. 사용자는 약한 레이어 배치, 경로 가독성 및 일관되지 않은 README 언어 링크를 반복적으로 표시했습니다.

## 결정

기존 Graphviz 지원 SVG/PNG 자산에 대한 결정적 레이어 밴드 전달로 `scripts/apply-layered-architecture-bands.mjs`를 사용합니다. 경로 지오메트리를 안정적으로 유지하되 도메인 이름의 레이어 밴드를 추가하고 렌더링하기 전에 레이어 포함 여부를 검증하세요.

## 결과

- 16개의 루트/모듈 아키텍처 및 클래스 스타일 다이어그램 쌍에 레이어 밴드를 추가했습니다.
- DynamoDB/K8s 액터/선거/상태 레이아웃에 대해 스택형 레이아웃과 열 밴드에 수평 행 밴드를 사용했습니다.
- 정규화된 README 언어는 모든 README 쌍에서 눈에 보이는 `English | 한국어` 형식으로 전환됩니다.

## 검증

- `node scripts/apply-layered-architecture-bands.mjs`: 모든 대상은 끝점, 굴곡, 교차, 여백, 제목 간격 및 레이어 봉쇄 failure가 0이라고 보고되었습니다.
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`: `diagrams=65 failures=0`.
- 밀착 시트와 개별 PNG 검사를 통해 루트, DynamoDB, K8s 및 Exposed JDBC 다이어그램을 다루었습니다.
- `xmllint --noout`, README 이미지 링크 검증 및 `git diff --check`를 통과했습니다.

## 향후 지침

- `Layer 4`와 같은 대체 이름을 허용하지 마십시오. 모든 밴드 라벨은 도메인에서 의미가 있어야 합니다.
- 행 기반 클러스터링이 다이어그램을 하나의 큰 밴드로 축소하는 경우 검토하기 전에 더 큰 캔버스나 열 기반 레이어 모델로 전환하세요.
- 의심스러운 다이어그램에 대한 개별 검사와 밀착 시트 분류를 유지합니다. 자동화된 게이트는 렌더링된 PNG 검토를 대체하지 않습니다.
