# 모듈 아키텍처 다이어그램 적용 범위

## 맥락

`leader-micrometer` 및 `leader-spring-boot`에는 이미 아키텍처 다이어그램이 있었지만 그리드와 같은 배치로 인해 관계를 따라가기가 어려웠습니다. `leader-etcd`, `leader-dynamodb` 및 `leader-consul`에는 README 아키텍처 다이어그램이 없습니다.

## 결정

모든 모듈을 동일한 행/열 그리드에 강제로 배치하는 대신 입력, 자동 구성, 선택자, 백엔드 클라이언트, 백엔드 상태 및 내보내기를 관계별로 그룹화하는 보다 자유로운 모듈 수준 레이아웃을 사용하세요. `scripts/generate-module-architecture-diagrams.mjs`에서 모듈 아키텍처 SVG를 생성한 다음 공유 Graphviz 증거/PNG 재생성 게이트를 실행합니다.

## 결과

`leader-micrometer` 및 `leader-spring-boot`는 이제 보다 명확한 관계 우선 아키텍처 레이아웃을 사용합니다. `leader-etcd`, `leader-dynamodb` 및 `leader-consul`는 이제 일치하는 SVG, DOT, 일반, Graphviz SVG 및 Graphviz PNG 증거 파일과 함께 영어 및 한국어 README 모두에 아키텍처 PNG 다이어그램을 포함합니다.

## 검증

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- `git diff --check`
- README 이미지 링크 검증: `missing=0`
- 시각적 밀착 시트: `.omx/artifacts/module-architecture-diagrams-contact-sheet.png`

## 향후 지침

백엔드 README에 아키텍처 다이어그램이 부족한 경우 동일한 변경 사항에 README 로케일 쌍을 추가하세요. 그래프에 여러 개의 독립적인 진입 경로가 있는 경우 균일한 그리드보다 관계 우선 배치를 선호합니다.
