# 예 README 다이어그램 새로 고침

## 맥락

`examples/*` README 파일에는 소스 파생 시나리오와 아키텍처 다이어그램이 필요했습니다. 몇몇 예에는 이미 시퀀스 다이어그램이 있었고 Kubernetes Lease, Kubernetes Operator 및 Rate Limiter에도 시퀀스 다이어그램이 필요했습니다.

## 결정

소스 코드를 워크플로 권한으로 사용하되, 새 모양을 도입하기 전에 동일한 유형의 기존 저장소 다이어그램을 시각적 기준선으로 사용하세요. 시퀀스 참가자 헤더는 알약이나 타원 배지가 아닌 모서리 반경이 작은 직사각형 상자여야 합니다. 카드 라벨은 문자 수로 시각적으로 추정할 뿐만 아니라 실제 다이어그램 글꼴을 사용하여 렌더링된 상자 너비를 기준으로 측정해야 합니다. 이제 Graphviz 증거 생성기가 이러한 규칙을 검증합니다.

## 결과

이제 모든 README 로캘 쌍 예제에는 시나리오 텍스트와 PNG 다이어그램 삽입이 포함됩니다. 부족한 예제를 위해 새로운 아키텍처 다이어그램이 추가되었고 워크플로우에 필요한 곳에 누락된 시퀀스 다이어그램이 추가되었습니다.

후속 패스에서는 Kubernetes Operator, Rate Limiter, Migration Gate, Tenant Aggregator, Webhook Poller, Ktor App 및 Batch Scheduler를 포함하여 렌더링된 제목이 상자를 초과하는 모든 예제 아키텍처 카드를 확장했습니다.

## 검증

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- README 이미지 링크 검증: `readmes=20 missing=0 svgEmbeds=0`
- `git diff --check`
- 시각적 밀착 시트: `.omx/artifacts/examples-readme-diagrams-contact-sheet.png`
- 오버플로 수정 밀착 인화지: `.omx/artifacts/fixed-architecture-overflow-contact-sheet.png`

## 향후 지침

새로운 README 다이어그램을 그리기 전에 동일한 다이어그램 유형의 기존 자산을 검사하고 소스 기반 이유 때문에 문서화된 차이가 필요한 경우를 제외하고 안정적인 모양 언어를 유지하세요.

생성된 SVG 카드 다이어그램의 경우 구성된 `Architects Daughter` 및 `Comic Mono` 글꼴 파일을 사용하여 렌더링된 텍스트를 측정합니다. 카드 텍스트가 상자 경계를 초과하면 배치가 실패합니다. PNG를 렌더링하기 전에 카드/캔버스를 넓히거나 레이아웃 방향을 변경하여 문제를 해결하세요.
