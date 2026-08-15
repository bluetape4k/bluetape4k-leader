# WIP - bluetape4k-leader

스냅샷: 2026-08-15 KST. `develop`의 현재 head는
`4b99338a1079ccf5d792b930238cf2f56f8f1929`이며, 선행 계약 train PR #663이
병합되었습니다. `0.5.0`은 tag, GitHub Release, Maven Central publication이
완료된 안정 릴리스입니다. 이 문서는 게시 후 현재 상태와 과거 `as-of`
스냅샷을 분리하여 기록합니다.

## 현재 방향

0.5.0 release-readiness, 문서/KDoc localization train, release-facing 문서
정합성 작업(PR #650), 게시 작업이 완료되었습니다. 현재 작업은 게시 기록을
변경하지 않고, `0.5.0` provenance를 독자용 문서와 생성 자산에 반영하는
후속 이슈 [#662](https://github.com/bluetape4k/bluetape4k-leader/issues/662)입니다.

## 0.5.0 release-readiness 완료 (history)

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

`0.6.0` milestone은 open 상태이며 현재 open issue는 32개입니다. 아래 목록은
그 milestone에 배정된 다음 라인의 범위입니다.

- Core/observability: [#463](https://github.com/bluetape4k/bluetape4k-leader/issues/463), [#533](https://github.com/bluetape4k/bluetape4k-leader/issues/533), [#535](https://github.com/bluetape4k/bluetape4k-leader/issues/535), [#559](https://github.com/bluetape4k/bluetape4k-leader/issues/559)
- Ktor: [#539](https://github.com/bluetape4k/bluetape4k-leader/issues/539), [#540](https://github.com/bluetape4k/bluetape4k-leader/issues/540), [#541](https://github.com/bluetape4k/bluetape4k-leader/issues/541), [#542](https://github.com/bluetape4k/bluetape4k-leader/issues/542)
- Spring/management: [#532](https://github.com/bluetape4k/bluetape4k-leader/issues/532), [#602](https://github.com/bluetape4k/bluetape4k-leader/issues/602), [#603](https://github.com/bluetape4k/bluetape4k-leader/issues/603), [#606](https://github.com/bluetape4k/bluetape4k-leader/issues/606), [#607](https://github.com/bluetape4k/bluetape4k-leader/issues/607)

## Open PRs

이 스냅샷 작성 시점에 공개된 open PR은 없었습니다.

## 현재 Release boundary

- 최신 안정 버전은 `0.5.0`이며 release commit은
  `721a9a3808f67489d2bdb8177734325981c24977`입니다.
- [GitHub Release 0.5.0](https://github.com/bluetape4k/bluetape4k-leader/releases/tag/0.5.0)은
  2026-08-06에 게시되었습니다.
- [Maven Central BOM 0.5.0](https://central.sonatype.com/artifact/io.github.bluetape4k.leader/bluetape4k-leader-bom/0.5.0)
  및 publishable module POM이 확인되었습니다.
- 버전 매뉴얼 manifest는 `0.5.0`과 위 release commit에 고정되어 있습니다.
- 다음 개발 범위는 open `0.6.0` milestone에서 관리합니다.

## Historical release boundary (2026-08-01 KST as-of)

- 최신 안정 버전은 `0.4.0`이며 release commit은 `17ab7f872c1f96318c73d3580729cac20a67e017`입니다.
- `CHANGELOG.md`와 [release preflight](docs/release/0.5.0-release-preflight.md)는 `0.5.0`을 unpublished 상태로 유지합니다.
- tag/release/publication은 별도 승인과 정확한 candidate ref 검증 없이는 생성하지 않습니다.
