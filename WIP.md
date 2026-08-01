# WIP - bluetape4k-leader

스냅샷: 2026-08-01 KST. `develop`의 현재 head는
`3a0cd0e43c48b24775cbc1cb0d024e460301e2ee`이며, 문서 정합성 PR [#650](https://github.com/bluetape4k/bluetape4k-leader/pull/650)은
병합되었고 연결 이슈 [#649](https://github.com/bluetape4k/bluetape4k-leader/issues/649)는 종료되었습니다.
`0.5.0`은 아직 태그, GitHub Release 또는 Maven Central publication이 없는 개발
라인입니다. 이 문서는 병합·게시 완료를 혼동하지 않고, 관찰된 검증 증거를 기준으로
상태를 갱신합니다.

## 현재 방향

0.5.0 release-readiness, 문서/KDoc localization train 및 release-facing 문서 정합성
작업(PR #650)은 완료되었습니다.
다음 단계는 게시 승인 전 preflight 증거를 유지하고, 별도 release로 약속되지
않은 Backlog 후보를 다음 마이너 라인에서 계획하는 것입니다. `0.5.0`을
게시된 릴리스로 표시하려면 tag, Maven Central publication, GitHub Release가
각각 별도의 승인과 증거를 가져야 합니다.

## 0.5.0 release-readiness 완료

| 범위 | 현재 증거 |
|---|---|
| 구현/회귀 이슈 | [#637–#646](https://github.com/bluetape4k/bluetape4k-leader/milestone/0.5.0) 및 Epic [#647](https://github.com/bluetape4k/bluetape4k-leader/issues/647) 모두 closed |
| 구현 PR | [PR #648](https://github.com/bluetape4k/bluetape4k-leader/pull/648) merged; source `e4dc2dbb…`, merge `7268feff…` |
| PR CI | [run #30691084868](https://github.com/bluetape4k/bluetape4k-leader/actions/runs/30691084868) success |
| post-merge `develop` CI | [run #30691656980](https://github.com/bluetape4k/bluetape4k-leader/actions/runs/30691656980) success |
| Java/Kotlin·Actions CodeQL | 수동 [run #30692366188](https://github.com/bluetape4k/bluetape4k-leader/actions/runs/30692366188) success; merge head exact |
| release-facing 문서 정합성 | [PR #650](https://github.com/bluetape4k/bluetape4k-leader/pull/650) merged at `3a0cd0e4…`; issue [#649](https://github.com/bluetape4k/bluetape4k-leader/issues/649) closed; [submit-gradle run #30694254927](https://github.com/bluetape4k/bluetape4k-leader/actions/runs/30694254927) success |
| 0.5.0 milestone | open issues `0`; milestone 자체는 publication 승인 없이 유지 |

주요 수정에는 R2DBC group state 격리, blocking interruption 보존, Spring
operational surface 정합성, JVM-global lease extender ownership, full-suite
timing 안정화가 포함됩니다. Detekt production coverage, ABI gate와 catalog-aware
CodeQL workflow도 함께 검증되었습니다.

## 0.5.0 문서/KDoc localization 완료

- Epic [#617](https://github.com/bluetape4k/bluetape4k-leader/issues/617)과 자식 이슈 [#618–#624](https://github.com/bluetape4k/bluetape4k-leader/milestone/0.5.0)는 모두 closed입니다.
- localization PR [#625](https://github.com/bluetape4k/bluetape4k-leader/pull/625)–[#631](https://github.com/bluetape4k/bluetape4k-leader/pull/631)과 proofreading PR [#632](https://github.com/bluetape4k/bluetape4k-leader/pull/632)는 merged입니다.
- bilingual visual companion Epic [#633](https://github.com/bluetape4k/bluetape4k-leader/issues/633)과 후속 PR [#636](https://github.com/bluetape4k/bluetape4k-leader/pull/636)도 closed/merged 상태입니다.

## 다음 마이너 라인 후보

현재 `0.6.0` milestone은 생성되지 않았습니다. 다음 후보는 Backlog에 남아
있으며, milestone 배정 전까지 committed release scope로 해석하지 않습니다.

- Core/observability: [#463](https://github.com/bluetape4k/bluetape4k-leader/issues/463), [#533](https://github.com/bluetape4k/bluetape4k-leader/issues/533), [#535](https://github.com/bluetape4k/bluetape4k-leader/issues/535), [#559](https://github.com/bluetape4k/bluetape4k-leader/issues/559)
- Ktor: [#539](https://github.com/bluetape4k/bluetape4k-leader/issues/539), [#540](https://github.com/bluetape4k/bluetape4k-leader/issues/540), [#541](https://github.com/bluetape4k/bluetape4k-leader/issues/541), [#542](https://github.com/bluetape4k/bluetape4k-leader/issues/542)
- Spring/management: [#532](https://github.com/bluetape4k/bluetape4k-leader/issues/532), [#602](https://github.com/bluetape4k/bluetape4k-leader/issues/602), [#603](https://github.com/bluetape4k/bluetape4k-leader/issues/603), [#606](https://github.com/bluetape4k/bluetape4k-leader/issues/606), [#607](https://github.com/bluetape4k/bluetape4k-leader/issues/607)

## Open PRs

현재 공개된 open PR은 없습니다.

## Release boundary

- 최신 안정 버전은 `0.4.0`이며 release commit은 `17ab7f872c1f96318c73d3580729cac20a67e017`입니다.
- `CHANGELOG.md`와 [release preflight](docs/release/0.5.0-release-preflight.md)는 `0.5.0`을 unpublished 상태로 유지합니다.
- tag/release/publication은 별도 승인과 정확한 candidate ref 검증 없이는 생성하지 않습니다.
