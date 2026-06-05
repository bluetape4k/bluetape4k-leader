# Issue 490 Layered Architecture Diagram Spec

## 상태

- Issue: #490 `docs(readme): refresh layered module architecture diagrams`
- Parent: #486
- Baseline: `develop` at `7313520b`
- Previous batch: #489 / PR #492 merged and closed
- Workflow type: E Maintenance, README diagram batch
- Required skills: `bluetape4k-workflow`, `bluetape4k-diagram`

## 문제

현재 root 및 module README의 architecture/class/overview diagram은 대부분
컴포넌트 박스와 선 중심으로 표현되어 있다. 사용자/API, framework integration,
core contract, backend adapter, external backend state, observability 같은
책임 경계가 시각적으로 분리되지 않기 때문에 README에서 모듈 역할과 의존 방향을
빠르게 읽기 어렵다.

또한 README locale switch 표기가 파일마다 다르다. 이번 batch에서 건드리는
README 쌍은 `English | 한국어` 형태로 정규화해야 한다.

## 범위

이번 batch는 root 및 module README diagram에 한정한다. `examples/*`의 scenario
및 flow 확장은 #491 범위로 남긴다.

주요 대상:

- Root README architecture/overview:
  - `bluetape4k-leader-architecture-01`
  - `root-readme-overview-01` inspect-only, because it is already a module map
    made of grouped cards rather than the root Leader Architecture Diagram
- BOM/module architecture:
  - `bluetape4k-leader-bom-architecture-01`
  - `leader-consul-architecture-01`
  - `leader-dynamodb-architecture-01`
  - `leader-etcd-architecture-01`
  - `leader-k8s-architecture-01`
  - `leader-micrometer-architecture-01`
  - `leader-spring-boot-architecture-01`
- Module class-style diagrams where a layered boundary improves readability:
  - `leader-core-class-01`
  - `leader-exposed-jdbc-class-01`
  - `leader-exposed-r2dbc-class-01`
  - `leader-hazelcast-class-01`
  - `leader-mongodb-class-01`
  - `leader-redis-lettuce-class-01`
  - `leader-redis-redisson-class-01`
  - `leader-zookeeper-class-01`

Scope exclusions:

- Example scenario/flow additions: #491
- Sequence diagram spacing/color changes already completed in #489
- Public API/Kotlin behavior changes
- New dependencies

## Source Of Truth

- Latest checked-out source code and README files on `develop@7313520b`
- Existing generator scripts:
  - `scripts/generate-module-architecture-diagrams.mjs`
  - `scripts/regenerate-readme-diagram-graphviz-evidence.mjs`
- Existing generated SVG/PNG and Graphviz evidence under
  `docs/images/readme-diagrams/`
- #490 live issue body updated after #489 merge
- GNO preflight:
  - `bluetape4k-github`: prior leader diagram PRs #441/#440 and related diagram
    correction cases found
  - `bluetape4k-docs`: no directly useful issue-490 prior lesson found

## Required Diagram Model

Layered diagrams must be visual models, not recolored Mermaid-like layouts.
Each changed node-and-connector diagram must explicitly encode layer membership
where the layer clarifies responsibility.

Canonical layer vocabulary, selected per diagram:

- User / Caller
- Application / Framework Integration
- Leader API / Core Contracts
- Backend Adapter / Elector Implementation
- Backend Client / External System
- Lock State / Lease State
- Observability / Events
- Examples / Runtime Workload

Not every diagram needs every layer. A layer is added only when it helps explain
boundaries or dependency direction.

## Geometry And Visual Requirements

- Layer bands must contain all assigned components fully.
- Components inside each layer must be vertically centered as a group, not
  bottom- or top-biased.
- Outer margins should be visually balanced across left, right, top, and
  bottom.
- Title/subtitle must have clear breathing room before the first layer.
- If routing or layer containment is cramped, enlarge the canvas before
  accepting awkward placement.
- Multi-segment connector bends should be 90 degrees.
- Connector endpoints must attach to component boundaries without 0-degree or
  tangent defects.
- Routes must not cross non-endpoint component interiors.
- Text blocks in cards and footer boxes must be centered as a group.
- Font roles must follow the diagram skill:
  - prominent titles/labels: `Architects Daughter`
  - details/captions/member lists: `Comic Mono`

## README Requirements

- Changed README files embed PNG only.
- Each changed PNG has a matching SVG.
- Changed node-and-connector diagrams have matching `.dot`, `.plain`,
  `-graphviz.svg`, and `-graphviz.png` evidence.
- Generated diagram labels remain English and are shared by English/Korean
  README files.
- Touched multilingual README pairs use the locale switch form:
  - English README: `English | [한국어](...)`
  - Korean README: `[English](...) | 한국어`

## Acceptance Criteria

1. Root architecture diagram clearly separates user/API, framework integration,
   core contracts, backend implementations, infrastructure/state, observability,
   and examples where applicable.
2. Module architecture/class diagrams use layer bands when they clarify module
   boundaries and backend dependency paths.
3. The generator emits deterministic geometry summaries before PNG rendering for
   every changed node-and-connector diagram.
4. The shared evidence gate reports `diagrams=65 failures=0`.
5. SVG XML validation passes for all README diagram SVGs.
6. README image-link validation reports no missing assets and no SVG embeds.
7. Rendered PNGs for all changed diagrams are inspected through a contact sheet,
   with individual inspection for dense or previously suspect diagrams.
8. `git diff --check` passes.
9. Spec review, plan review, and implementation review each report:
   - `P0 = 0`
   - `P1 = 0`

## Risks

- P1 risk: adding layer bands without generator gates could make diagrams look
  cleaner while still leaving bad endpoints, unbalanced margins, or components
  outside bands.
- P1 risk: trying to fit every module into a fixed canvas can recreate the same
  cramped line-routing defects. Canvas dimensions must be content-driven.
- P2 risk: broad README locale switch normalization can create noisy diffs. Keep
  locale switch edits limited to touched README pairs.
- P2 risk: class-style diagrams may not benefit from full layer bands. Use a
  compact boundary/layer treatment only when it improves the diagram.

## Stop Condition

Stop implementation if any mandatory geometry gate cannot be encoded in the
generator. In that case, update the plan with the missing validator before
editing more diagrams.
