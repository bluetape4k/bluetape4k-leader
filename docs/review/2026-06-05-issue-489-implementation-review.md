# 이슈 489 구현 검토

- 문제: #489 — `docs(diagram): apply semantic lock-state line colors`
- 상위: #486
- 검토 유형: 현지 구현 검토
- 범위: README 다이어그램 생성기, 증거 게이트 및 변경된 다이어그램 자산

## 평결

- P0 = 0
- P1 = 0
- 게이트: 통과

## 조사 결과

P0/P1 차단제가 없습니다.

## Step DoD

| Step | Status | Evidence |
|---|---:|---|
| Spec | PASS | `docs/superpowers/specs/2026-06-05-issue-489-lock-state-line-colors-design.md` |
| Spec review | PASS | `docs/review/2026-06-05-issue-489-spec-review.md`; `P0 = 0`, `P1 = 0` |
| Plan | PASS | `docs/superpowers/plans/2026-06-05-issue-489-lock-state-line-colors-plan.md` |
| Plan review | PASS | `docs/review/2026-06-05-issue-489-plan-review.md`; `P0 = 0`, `P1 = 0` |
| Baseline evidence repair | PASS | `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check` now reports `diagrams=65 failures=0` |
| Semantic route application | PASS | `node scripts/apply-lock-state-line-colors.mjs` reports 12 target sequence diagrams and 136 semantic routes |
| Sequence call spacing | PASS | `node scripts/compact-sequence-call-spacing.mjs` compacted 11 generic sequence diagrams without changing route semantics |
| ZooKeeper generator geometry | PASS | `node scripts/generate-zookeeper-scheduler-readme-diagrams.mjs` reports `badEndpointAngle=0`, `badBends=0`, `interiorCrossings=0`, `marginImbalance=0` for 4 diagrams |
| Semantic color gate | PASS | Evidence script checks `data-route-tone`, stroke color, and matching semantic arrow markers for 16 target diagrams |
| SVG parsing | PASS | `find docs/images/readme-diagrams -maxdepth 1 -name '*.svg' -print0 \| xargs -0 xmllint --noout` |
| README embeds | PASS | README image check reports `README image links ok: files=75` and no README SVG embeds |
| Visual QA | PASS | Contact sheets `.omx/artifacts/issue-489-semantic-route-contact-sheet.png` and `.omx/artifacts/issue-489-compacted-sequence-contact-sheet.png`; individual PNG inspection for K8s, ZooKeeper, Spring Boot, Lettuce, Redisson, and Hazelcast sequence diagrams |
| Whitespace | PASS | `git diff --check` |

## 리뷰 노트

- 증거 파서가 기존 `dash` 건너뛴 반환 커넥터를 계산하므로 K8s 시퀀스에는 이제 9개의 Graphviz 경로가 있습니다. 이는 새로운 시각적 커넥터가 아닌 증거 범위에 대한 수정입니다.
- 이전 너비가 텍스트 오버플로 없이 `ZooKeeperLeaderElector`에 맞을 수 없기 때문에 ZooKeeper 스케줄러 시퀀스 캔버스가 넓어지고 참가자 헤더가 확대되었습니다. 이는 비좁은 레이아웃을 수용하기 전에 도면 영역을 확대하는 다이어그램 기술 규칙을 따릅니다.
- 문제 범위 외부의 기존 예제 시퀀스 다이어그램은 이미 ZooKeeper 생성기의 잠금 상태 의미 색상 지정이 없는 한 변경되지 않은 채로 남아 있었습니다.
- 사용자 검토 결과 일반 시퀀스 함수 호출 간격이 너무 넓은 것으로 나타났습니다. 수정 사항은 원시 Y축 크기 조정 대신 전체 메시지 그룹을 압축하므로 고정 레이블 상자가 원래 화살표 간격을 유지합니다.

## 잔여 위험

- P2: 일반 의미 톤 적용기는 경로 순서에 따라 기존 시퀀스 경로를 매핑합니다. 향후 소스 모델 재생성은 이를 보다 풍부한 도메인 모델로 대체할 수 있지만 현재 증거 게이트는 오래된 획/화살표 불일치를 방지합니다.
