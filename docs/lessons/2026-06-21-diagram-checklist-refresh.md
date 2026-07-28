# 2026-06-21 다이어그램 체크리스트 새로고침

## 맥락

README 및 예제 README 다이어그램 세트는 이미 Graphviz에서 벗어났지만 렌더링된 SVG에는 여전히 체크리스트 드리프트가 있었습니다.

- 명시적인 `markerUnits`가 없는 커넥터 마커
- 마커 헤드에 점선 커넥터 스타일이 묻어남
- 형상 감사에 failure한 날카로운 직교 커넥터 모서리
- 공유 필기 글꼴 쌍 외부의 대체 글꼴
- 보이는 이미지에 소스/프로세스 문구를 사용한 차트 메모
- 실제 서비스 또는 인프라를 나타내는 카드에 공유 카탈로그 아이콘이 누락되었습니다.

## 결정

현재 독자용 레이아웃을 유지하고 체크리스트 수준 드리프트만 수정합니다. 수리 패스는 `scripts/repair-readme-svg-checklist.mjs`에 중앙 집중화되어 있으므로 발전기 출력 변경 후 향후 새로 고침을 다시 실행할 수 있습니다.

`docs/assets/leader-election-workbench.png` 아래의 README 영웅 래스터는 README 다이어그램/차트 소스 자산이 아닌 그림입니다. 이전 영웅 이미지 강의에서 기록한 대로 설계상 PNG 전용으로 유지됩니다.

아이콘 배치는 `scripts/apply-readme-svg-icons.mjs`에 의해 별도로 처리됩니다. 스크립트는 `/Users/debop/work/bluetape4k/bluetape4k-wiki/docs/icons`의 공유 카탈로그 아이콘만 사용하고, 코드 전용 카드를 텍스트로만 유지하며, 후속 감사에서 출처를 검증할 수 있도록 `data-bluetape4k-icon`와 `data-icon-source` 메타데이터를 내보냅니다.

## 검증

README 다이어그램/차트 SVG를 재생성하거나 편집한 후 다음을 실행하십시오.

```bash
node scripts/repair-readme-svg-checklist.mjs --check
node scripts/apply-readme-svg-icons.mjs
xmllint --noout docs/images/readme-diagrams/*.svg docs/images/readme-charts/*.svg
python3 /Users/debop/.codex/skills/bluetape4k-diagram/references/diagram-geometry-audit.py docs/images/readme-diagrams/*.svg docs/images/readme-charts/*.svg
rg -n "Arial|sans-serif|monospace|Comic Sans MS|Source:|Generated|validation|Graphviz|DOT|context-stroke" docs/images/readme-diagrams docs/images/readme-charts -g '*.svg'
git diff --check
```

CairoSVG를 통해 PNG를 렌더링하고 다이어그램 세트가 완료된 것으로 보고하기 전에 밀착 시트와 고위험 원본을 검사합니다.

이번 새로 고침의 경우 아이콘 감사에서는 61개의 카탈로그 아이콘, 0개의 누락된 카탈로그 소스, 0개의 의심스러운 코드 카드 배치가 포함된 42개의 SVG 파일을 발견했습니다.

후속 검토에서는 기본 XML/렌더링 검사가 증명하지 못한 추가 시각적 회귀를 발견했습니다. 시퀀스 다이어그램은 모범 사례 참가자/생명선/메시지 레인 스타일을 따라야 하며, 다이어그램이 구부러진 선과 대각선을 혼합할 때 커넥터 경로는 직각을 유지해야 하며, 마커 헤드는 점선에서 실선으로 유지되어야 하며, 비순차 커넥터는 카드 내부를 통해 구부러지지 않아야 합니다.

이제 복구 스크립트는 간단한 대각선 커넥터 경로를 둥근 직교 경로로 정규화하고, 상속된 대시 스타일에 대해 마커 하위 경로를 강화하고, 시퀀스 화살촉을 명시적으로 유지합니다. 아이콘 스크립트는 관리되는 아이콘 이미지를 배치하기 전에 제거하고 다시 적용하므로 크기 및 위치 변경 사항은 멱등성을 갖습니다. Redis가 아닌 아이콘이 SVG 소스에 있었지만 렌더링된 여러 카드에서는 너무 작았습니다. 다시 렌더링하기 전에 표시되는 최소 아이콘 크기가 늘어났습니다.

후속 조치에 사용되는 추가 검증:

- `straightDiagonal=0`
- `smallFilledArrowMarkers=0`
- `diagonalLineSegments=0`
- `markerDashLeaks=0`
- `missingFontPair=0`
- `weakRouteStrokeStyles=0`
- `weakCardStrokeStyles=0`
- `nonSequenceCardInteriorBends=0`
- 아이콘 배포: Redis 21, Consul 6, DynamoDB 8, etcd 4, Kubernetes 4, 데이터베이스 6, Prometheus 6, Grafana 4, ZooKeeper 1, Spring Boot 1
- 대표 육안 검사: 시퀀스 밀착 시트, 전체 다이어그램 밀착 시트, Consul 시퀀스/흐름, DynamoDB 시퀀스, Kubernetes 시퀀스, Prometheus 아키텍처, 전략적 선택 흐름 및 리더 코어 클래스

이후의 시각적 검토에서는 유효한 XML과 첫 번째 지오메트리 감사에서 여전히 너무 작은 채워진 화살표 마커, 약한 2px 경로 스트로크, 한 번의 얇은 카드 스트로크 및 실제 카드 경계가 아닌 `Ownership Gate` 근처에서 시각적으로 끝나는 경로가 허용되는 것으로 나타났습니다. 이제 복구 스크립트는 채워진 화살촉을 README 규모의 10x10 마커 계열로 정규화하고, 중요한 스타일 재정의로 대시 상속을 차단하고, 다중 지점 경로 내의 대각선 세그먼트를 거부하고, 기준선 아래에 있는 경로/카드 획 스타일을 높입니다. 저장소 아키텍처 개요가 수동으로 다시 그려졌으므로 소유권 게이트는 모호한 빈 공간 화살표 끝점이 있는 결정 다이아몬드 대신 수직 경계가 부착된 둥근 카드입니다.

최종 전체 크기 PNG 검토에서는 밀착 인화와 XML 검사만으로는 노출되지 않은 세 가지 체크리스트 드리프트 클래스가 남아 있음을 발견했습니다.

- 촘촘하게 둥근 커넥터 수리로 인해 예상되는 `Q` 기반 직교 굽힘 대신 입방형 `C` 곡선이 생성되었습니다.
- 여러 시퀀스 다이어그램에는 CSS를 통해 `marker-end`가 있었지만 마커 본체는 `fill="none" stroke="none"`로 렌더링되어 PNG 출력에서 화살촉이 보이지 않게 되었습니다.
- 클래스/아키텍처 다이어그램에는 여전히 기준선 획 너비 아래에 인라인 마커 경로가 있었습니다.

이제 복구 스크립트는 `Q` 굽힘을 2차 방정식으로 엄격하게 유지하고, 원래 마커 본문이 투명할 때 마커 ID에서 화살표 마커 색상을 정규화하고, 인라인 마커 경로/라인 스트로크를 최소 2.5px로 높입니다. 최종 시각적 패스에서는 아키텍처, 흐름, 시나리오, 시퀀스, 클래스/ERD, 차트 및 루트/코어 그룹에 대한 새로 고쳐진 밀착 시트를 검사하고 `examples-cache-warmer-architecture-01`, `examples-cache-warmer-flow-01`, `examples-migration-gate-scenario-01`, `leader-core-sequence-02`, `leader-core-sequence-03`, `leader-hazelcast-sequence-02`, `leader-hazelcast-sequence-03`, `leader-spring-boot-sequence-01`, `leader-spring-boot-architecture-01`, `leader-dynamodb-architecture-01`, `leader-exposed-core-erd-01`, `leader-mongodb-class-01` 및 `leader-zookeeper-class-01`.

최종 감사 증거:

- `connectorCurves=0`
- `diagonalLineSegments=0`
- `smallFilledArrowMarkers=0`
- `markerDashLeaks=0`
- `sequenceArrowMarkersNone=0`
- `sequenceMessagePathsWithoutEffectiveMarker=0`
- `weakInlineMarkerStrokes=0`
- `missingFontPair=0`
- `weakRouteStrokeStyles=0`
- `weakCardStrokeStyles=0`

이후의 사용자 시각적 검토에서는 이전 패스가 여전히 밀착 시트 및 SVG 수준 검사에 과도하게 의존하고 있음이 드러났습니다. 특정 오류가 클러스터링되었습니다.

- 예제 시퀀스 다이어그램은 leader-core 모범 사례 스타일을 일관되게 따르지 않았습니다.
- 예제 흐름도는 여전히 수직 작업 순서 순서도 대신 수평 시나리오 흐름으로 읽혀집니다.
- 얕은 측면 각도로 연결된 여러 카드 커넥터, 서로 관련 없는 카드가 교차되거나 시각적으로 너무 빡빡한 둥근 굽힘 사용
- `leader-hazelcast-class-01`는 SVG 마커 본체가 단단해 보이는 경우에도 PNG에서 점선 마커 아티팩트를 표시했습니다.

이 수리를 위해 참가자 헤더, 수직 수명선, 수평 메시지 레인 및 더 큰 행 간격을 사용하여 예제 시퀀스 세트가 재생성되었습니다. 예시 흐름 세트는 수직 흐름도 패턴으로 재생성되었습니다. Hazelcast 클래스 다이어그램은 이제 상속된 마커 렌더링에 의존하는 대신 점선 화살촉을 직접적인 솔리드 형상으로 그립니다.

두 가지가 모두 충족될 때까지 향후 README 다이어그램 작업을 완료로 표시하지 마세요.

1. CairoSVG 렌더링 후 사용자가 보고한 파일이 전체 크기 PNG로 열렸습니다.
2. 패턴 전체 파일은 밀착 시트를 통해 검사되었으며 의심스러운 썸네일은 전체 크기로 다시 열립니다.

이 수리에 대한 추가 증거:

- 전체 크기 PNG 검토: `bluetape4k-leader-sequence-03`, `examples-batch-scheduler-sequence-01`, `examples-cache-warmer-architecture-01`, `examples-cache-warmer-flow-01`, `examples-cache-warmer-sequence-01`, `examples-consul-maintenance-sequence-01`, `examples-dynamodb-export-sequence-01`, `leader-etcd-architecture-01`, `leader-exposed-core-erd-01`, `leader-exposed-jdbc-class-01`, `leader-exposed-r2dbc-class-01` 및 `leader-hazelcast-class-01`
- 밀착 인화지 검토: `examples-*-sequence-01` PNG 17개 모두 및 `examples-*-flow-01` PNG 17개 모두
- 최종 정적 감사: `files=109`, `connectorPaths=437`, `msgPaths=285`, `missingFont=0`, `markerSmall=0`, `sequenceStyle=0`, `flowStyle=0`, `hazelDashMarkers=0`, `badConnectorSegments=0`, `seqMissing=0`

이후 스타일 검토에서는 전체 `examples-*` 세트가 모범 사례 카탈로그에 비해 여전히 너무 거칠어 보이는 것으로 나타났습니다. 향후 검사는 "경로에 Q가 포함되어 있음"에서 중지되어서는 안 됩니다. 먼저 날카로운 `L/H/V`와 작은 가짜 둥근 후보를 표시한 다음 렌더링된 PNG를 열고 모서리가 실제로 둥근 것으로 읽혀지는지 검증합니다.

이 수리를 위해 모든 68개의 `examples-*` 다이어그램이 하나의 모범 사례 템플릿 제품군에서 재생성되었습니다.

- 아키텍처: 의미 체계 밴드와 두꺼운 카드/경로 스트로크가 있는 계층형 토폴로지
- 흐름: 트리거, 준비, 선택, 리더 작업 및 결과 밴드가 포함된 수직 흐름도
- 시나리오: success, 건너뛰기 및 재시도 레인이 포함된 워크플로 분기
- 시퀀스: leader-core 스타일 참가자 헤더, 수명선, 활성화 표시줄, 대체 분기 프레임 및 넓은 메시지 간격

최종 전체 세트 감사에서는 예제가 아닌 오래된 다이어그램에 남아 있던 엄격한 `Q` 후보도 복구했습니다.

- `leader-core-sequence-02`
- `leader-core-sequence-03`
- `leader-mongodb-class-01`
- `leader-zookeeper-class-01`

모든 예제 재생에 대한 추가 증거:

- 재생성된 SVG/PNG 쌍: 68 `examples-*` 다이어그램
- 후처리 멱등성: `repair-readme-svg-checklist.mjs --check` => `would_update=0` 및 `apply-readme-svg-icons.mjs` => `would_update=0 icons=0`
- 전체 세트 기하학 감사: 109개 다이어그램/차트 SVG, 모두 `geometry_failures=0`
- 정적 스타일 감사: `files=109`, `missingFont=0`, `smallMarkers=0`, `dashedMarkers=0`
- 전체 크기 PNG 검토: `examples-cache-warmer-flow-01`, `examples-dynamodb-export-sequence-01`, `examples-zookeeper-scheduler-scenario-01`, `examples-prometheus-dashboard-architecture-01`, `leader-mongodb-class-01` 및 `leader-zookeeper-class-01`

후속 검토에서는 일부 예제 아키텍처, 흐름 및 시나리오 다이어그램이 순서가 지정된 작업을 나타냄에도 불구하고 여전히 수평 레인 다이어그램으로 읽히는 것으로 나타났습니다. `examples-batch-scheduler-architecture-01` 모양이 있는 예제 다이어그램은 이제 대신 수직 차선 열을 사용합니다. Flow 및 시나리오 다이어그램은 사이드 레인과 재결합 카드 경계를 통해 라우팅되는 재시도 및 건너뛴 분기를 통해 기본 시나리오를 위에서 아래로 유지합니다. 향후 검토에서는 예제 세트를 수락하기 전에 `examples-batch-scheduler-architecture-01`, `examples-cache-warmer-flow-01` 및 `examples-cache-warmer-scenario-01`에 대한 전체 크기 PNG를 열어야 합니다.

또 다른 검토에 따르면 광범위한 모범 사례 레이아웃을 일치시키는 것만으로는 충분하지 않습니다. 선 두께도 기존 `bluetape4k-projects` 및 `bluetape4k-javers` README 제품군과 일치해야 합니다. 광범위한 다이어그램 템플릿을 변경하기 전에 하나 이상의 `projects` 및 하나의 `javers` 클래스/아키텍처 PNG를 열고 커넥터 무게를 비교한 다음 전체 크기로 리더 PNG를 검사하십시오. 여기서 발견된 결함은 통합 카드를 통해 종속성을 라우팅하는 `leader-ktor-architecture-01`와 라인 색상과 일치하지 않는 마커 헤드가 있는 카드를 통한 `leader-mongodb-class-01` 라우팅 클래스/사용 커넥터였습니다.
