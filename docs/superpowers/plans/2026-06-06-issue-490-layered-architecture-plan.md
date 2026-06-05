# Issue 490 Layered Architecture Diagram Plan

## 목표

#490 범위의 root/module architecture 및 class-style README diagrams를
layer-aware visual model로 재작성하고, `bluetape4k-diagram` gate를 통과한
증거와 rendered preview를 PR body에 남긴다.

## 단계별 작업

### 1. Baseline Inventory

DoD:

- Current branch/worktree 확인
- 대상 README와 diagram asset 목록 확정
- 기존 generator/evidence 구조 확인
- Baseline validation:
  - `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`

### 2. Generator Model Refactor

DoD:

- `scripts/generate-module-architecture-diagrams.mjs`를 layer-aware diagram
  model로 변경
- 각 diagram이 필요한 layer bands를 명시하도록 데이터 모델 확장
- Components는 layer band 내부에 포함되고 중앙 정렬되도록 배치
- Canvas size는 diagram별 content-driven 값으로 조정
- Graphviz evidence와 final SVG의 node/route family가 대응되도록 유지

### 3. Geometry Gate 강화

DoD:

- Generator가 changed architecture/class diagrams에 대해 compact summary를
  출력:
  - nodes
  - routes
  - segments
  - badEndpointAngle
  - badBends
  - interiorCrossings
  - marginImbalance
  - titleGap
  - layerContainment
- Gate failure는 PNG 렌더링 전에 실패하도록 구성
- Layer containment, title/subtitle gap, balanced margins, endpoint approach,
  non-endpoint interior crossing을 검증

### 4. Render And Evidence Regeneration

DoD:

- Module architecture generator 실행
- `scripts/regenerate-readme-diagram-graphviz-evidence.mjs`로 changed diagrams의
  `.dot`, `.plain`, `-graphviz.svg`, `-graphviz.png` evidence 갱신
- Final SVG/PNG asset pair 갱신

### 5. README Locale Switch And Embeds

DoD:

- Changed README pairs only: language switch를 `English | 한국어` 형태로 정규화
- README embeds는 PNG only 유지
- 주변 README prose는 불필요하게 바꾸지 않음

### 6. Visual QA

DoD:

- Changed architecture/class diagrams contact sheet 생성
- Dense diagrams는 개별 PNG로 확대 확인
- 확인 항목:
  - layer band 안의 component vertical alignment
  - top/bottom/left/right margin balance
  - title/subtitle breathing room
  - connector endpoint angle and line routing
  - text overflow and font role

### 7. Validation

DoD:

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`
- `find docs/images/readme-diagrams -maxdepth 1 -name '*.svg' -print0 | xargs -0 xmllint --noout`
- README image-link check: no missing assets and no SVG embeds
- `git diff --check`

### 8. Review, Lesson, PR

DoD:

- Implementation review artifact:
  - `docs/review/2026-06-06-issue-490-implementation-review.md`
  - `P0 = 0`, `P1 = 0`
- Lesson artifact:
  - `docs/lessons/2026-06-06-issue-490-layered-architecture-diagrams.md`
- Commit with Lore protocol
- Push branch
- Create PR assigned to `debop`, milestone `0.4.0`, labels `documentation`,
  `design`
- PR body verified live with final `## DoD Status`
- PR comment and formal PR review evidence added
- CI monitored until checks are complete or an explicit docs-only exception is
  recorded

## Target Scope Order

1. Root overview and root leader architecture diagrams
2. Spring Boot and Micrometer architecture diagrams
3. Backend architecture diagrams: Consul, DynamoDB, Etcd, K8s
4. Core/backend class-style diagrams with boundary/layer treatment where useful
5. BOM architecture diagram

## Non-Goals

- Do not add example scenario/flow diagrams; #491 owns that work.
- Do not change Kotlin source or public API.
- Do not introduce new dependencies.
- Do not normalize untouched README pairs.

## Risk Controls

- If a diagram becomes cramped, increase canvas dimensions rather than reducing
  margins or accepting poor line routing.
- If full layer bands make a class-style diagram harder to read, use fewer
  boundary bands and document the reason in the implementation review.
- If a generator cannot prove a mandatory geometry rule, stop and add the
  validator before rendering more assets.
