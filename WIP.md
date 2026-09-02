# WIP - bluetape4k-leader

기준일: 2026-09-02 KST. `1.0.0` tag, GitHub Release, Maven Central publication이
완료되었고 `develop`은 배포 후 유지보수를 진행합니다. 중앙 catalog는 전체
stable BOM을 포함한 immutable SHA
`3c203aa9f8ba80685aac766c5fb8f24e23d0058e`로 수렴했습니다.

## 현재 방향

`1.0.0` 정식 배포와 catalog 후속 반영은 완료되었습니다. `1.0.0-post`의
[#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)는 중앙 EN/KO
1.0 versioned manual을 exact release commit에 게시하고 README 링크를 안정 경로로
전환하면서 닫습니다. 이후 `develop` 변경은 다음 명시적인 release train에서
별도로 버전 승격합니다.

## 최근 반영

| 영역 | 현재 증거 |
|---|---|
| 1.0.0 release | [PR #861](https://github.com/bluetape4k/bluetape4k-leader/pull/861), tag/release commit `e70146330302758f563a46b7286e3ce25f1bac49`, GitHub Release와 Maven Central publication을 완료했습니다. |
| Stable catalog handoff | [PR #862](https://github.com/bluetape4k/bluetape4k-leader/pull/862)에서 CI와 settings의 catalog ref를 최종 Dependencies 2.0.0 SHA로 수렴했습니다. |
| Backend diagnostics | [Issue #766](https://github.com/bluetape4k/bluetape4k-leader/issues/766)의 공통 probe 계약과 built-in backend·Ktor·Spring 적용을 PR #812, #813, #814, #816, #817로 반영했습니다. |
| Observability policy | `UNKNOWN` bounded reason, active probe, Micrometer·Spring health, Ktor route, Prometheus alert/runbook을 PR #819, #820, #822, #823으로 고정하고 [#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)의 1.0 manual에 승격했습니다. |

## 현재 Release boundary

- 최신 안정 버전은 `1.0.0`이며 release commit은
  `e70146330302758f563a46b7286e3ce25f1bac49`입니다.
- [GitHub Release 1.0.0](https://github.com/bluetape4k/bluetape4k-leader/releases/tag/1.0.0)과
  [Maven Central BOM 1.0.0](https://central.sonatype.com/artifact/io.github.bluetape4k.leader/bluetape4k-leader-bom/1.0.0)을 게시했습니다.
- 중앙 [Leader 1.0 EN manual](https://bluetape4k.github.io/manual/bluetape4k-leader/1.0/)과
  [Leader 1.0 KO manual](https://bluetape4k.github.io/ko/manual/bluetape4k-leader/1.0/)은
  위 exact release commit에 고정됩니다.
- 현재 build catalog ref는
  `3c203aa9f8ba80685aac766c5fb8f24e23d0058e`입니다.

## 이전 기준일 (2026-09-02 release-prep)

- 당시 최신 안정판은 `0.5.0`이었고 `1.0.0` release-prep #860과 중앙 catalog
  promotion을 진행했습니다.
- 당시 중앙 매뉴얼의 release pin은 `0.5.0`을 보존했으며, #774의 관측성
  runbook은 1.0.0 배포 후 승격하도록 명시했습니다.
