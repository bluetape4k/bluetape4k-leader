# WIP - bluetape4k-leader

스냅샷: 2026-08-01 KST. `0.5.0`은 아직 태그, GitHub Release 또는 Maven
Central publication이 없는 개발 라인입니다. 2026-08-01 7-Tier review에서
발견한 release-blocking 이슈는 Epic [#647](https://github.com/bluetape4k/bluetape4k-leader/issues/647)와
그 하위 이슈 [#637–#646](https://github.com/bluetape4k/bluetape4k-leader/milestone/0.5.0)로
추적합니다. 이 문서는 병합·게시 완료를 주장하지 않으며, 각 이슈의 DoD와
정확한 CI/릴리스 증거를 확인한 뒤에만 상태를 갱신합니다.

## 현재 방향

현재 방향은 이 release-readiness train을 먼저 닫는 것입니다. R2DBC 상태
격리, blocking interruption, Spring 운영 surface, Detekt/ABI/CodeQL gate,
full-suite timing, release-state 문서를 각각 검증합니다. 모든 항목이 정확한
head에서 green이 되기 전에는 `0.5.0`을 게시된 릴리스로 표시하지 않습니다.

## 0.5.0 release-readiness 대기열

| 우선순위 | 이슈 | 마일스톤 | 메모 |
|---|---|---|---|
| P1 | [#637](https://github.com/bluetape4k/bluetape4k-leader/issues/637) R2DBC group state 격리 | 0.5.0 | 로컬 구현·H2/PostgreSQL/MySQL 검증 완료; exact-head CI/merge 대기 |
| P1 | [#638](https://github.com/bluetape4k/bluetape4k-leader/issues/638) unsupported readiness false `UP` 방지 | 0.5.0 | 로컬 구현·Spring 회귀 검증 완료; exact-head CI/merge 대기 |
| P1 | [#639](https://github.com/bluetape4k/bluetape4k-leader/issues/639) blocking interruption 보존 | 0.5.0 | 로컬 구현·4 backend 회귀 검증 완료; exact-head CI/merge 대기 |
| P1 | [#640](https://github.com/bluetape4k/bluetape4k-leader/issues/640) 0.4.0→0.5.0 ABI gate | 0.5.0 | 16 artifact 로컬 gate 통과; exact-head release 검증 대기 |
| P1 | [#641](https://github.com/bluetape4k/bluetape4k-leader/issues/641) CodeQL catalog centralization | 0.5.0 | catalog pin workflow·actionlint 통과; exact-head CodeQL 대기 |
| P1 | [#642](https://github.com/bluetape4k/bluetape4k-leader/issues/642) full-suite lease timing 안정화 | 0.5.0 | DynamoDB/Redisson 반복 테스트와 전체 suite 통과; exact-head CI 대기 |
| P1 | [#643](https://github.com/bluetape4k/bluetape4k-leader/issues/643) production Detekt wiring | 0.5.0 | root lifecycle·zero-source guard와 module evidence 확인; exact-head CI 대기 |
| P1 | [#644](https://github.com/bluetape4k/bluetape4k-leader/issues/644) selected backend operational surfaces | 0.5.0 | blocking/suspend/multi-backend 선택과 unsupported 상태 검증 완료; exact-head CI 대기 |
| P1 | [#645](https://github.com/bluetape4k/bluetape4k-leader/issues/645) 0.5.0 release-state 문서 | 0.5.0 | preflight/WIP boundary 갱신; publication·merge 미수행 |
| P1 | [#646](https://github.com/bluetape4k/bluetape4k-leader/issues/646) JVM-global extender ownership | 0.5.0 | conflict fail-fast·ref-count close-order 회귀 검증 완료; exact-head CI/merge 대기 |

기존 보안·Ktor·추가 운영 backlog는 이 release-readiness train과 섞지 않고
다음 마이너 라인에서 별도로 계획합니다.

## Open PRs

이 종료 문서 분기 이전에는 공개 PR이 없었습니다.

## 최근 완료됨

- [#529](https://github.com/bluetape4k/bluetape4k-leader/issues/529)는 Micrometer Observation 및 OpenTelemetry bridge를 추가했습니다.
- [#530](https://github.com/bluetape4k/bluetape4k-leader/issues/530)는 metric tag cardinality control을 추가했습니다.
- [#534](https://github.com/bluetape4k/bluetape4k-leader/issues/534)는 Prometheus alert rule 및 leader runbook을 추가했습니다.
- [#538](https://github.com/bluetape4k/bluetape4k-leader/issues/538)는 Spring Boot configuration metadata 및 startup diagnostics를 추가했습니다.
- [#647](https://github.com/bluetape4k/bluetape4k-leader/issues/647)은 이 release-readiness train을 묶는 Epic이며, 하위 이슈와 exact-head 증거가 모두 닫힌 뒤에만 종료합니다.

## 메모 새로 고침

- 2026-08-01 KST에 `gh`로 tag/release와 milestone 상태를 확인했습니다.
- latest published version은 `0.4.0`이며 release commit은 `17ab7f872c1f96318c73d3580729cac20a67e017`입니다.
- `CHANGELOG.md`와 [release preflight](docs/release/0.5.0-release-preflight.md)는 `0.5.0`을 unpublished 상태로 유지합니다.
- exact-head CI, ABI, manual validation, publication dispatch 증거가 없는 상태에서 tag/release/milestone 종료를 추론하지 않습니다.
