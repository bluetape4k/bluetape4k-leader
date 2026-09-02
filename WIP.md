# WIP - bluetape4k-leader

기준일: 2026-09-02 KST. `0.5.0`은 tag, GitHub Release, Maven Central publication이
완료된 최신 안정 릴리스이며, 현재 `develop`은 1.0.0 release-prep #860을 수행합니다.
중앙 catalog는 Projects/Exposed 안정 버전이 승격된 immutable SHA
`8efed120b91c4e1b1cfbfe1269321df325b08aef`를 사용합니다. release-pinned manual은
1.0.0 배포 이후 #774에서 별도로 승격합니다.

## 현재 방향

`1.0.0` milestone은 open 상태이며 release-prep #860 한 건이 열려 있고 200건이
닫혔습니다. PR 생성 전 열린 PR은 0개입니다. `1.0.0-post` milestone의 유일한 열린
이슈인 [#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)의
observability 운영 정책·경보·runbook은 완료되었고, `1.0.0` release train 이후
새 API를 포함한 versioned manual을 승격하는 작업만 남았습니다. 따라서 현재
manual pin은 변경하지 않습니다.

`1.1.0` milestone은 생성되어 있지만 열린 이슈가 없습니다. `1.0.0` release/tag,
Maven publication, versioned manual 승격은 별도 release 승인과 candidate 검증이
필요합니다.

## 최근 반영

| 영역 | 현재 증거 |
|---|---|
| Backend diagnostics | [Issue #766](https://github.com/bluetape4k/bluetape4k-leader/issues/766)의 공통 probe 계약과 built-in backend·Ktor·Spring 적용이 [PR #812](https://github.com/bluetape4k/bluetape4k-leader/pull/812), [PR #813](https://github.com/bluetape4k/bluetape4k-leader/pull/813), [PR #814](https://github.com/bluetape4k/bluetape4k-leader/pull/814), [PR #816](https://github.com/bluetape4k/bluetape4k-leader/pull/816), [PR #817](https://github.com/bluetape4k/bluetape4k-leader/pull/817)로 반영되었습니다. |
| Observability policy | `UNKNOWN` bounded reason, active probe, Micrometer·Spring health 연결, Ktor route, Prometheus alert/runbook을 [PR #819](https://github.com/bluetape4k/bluetape4k-leader/pull/819), [PR #820](https://github.com/bluetape4k/bluetape4k-leader/pull/820), [PR #822](https://github.com/bluetape4k/bluetape4k-leader/pull/822), [PR #823](https://github.com/bluetape4k/bluetape4k-leader/pull/823)로 고정했습니다. 운영 정책은 완료되었고 versioned manual 승격은 [#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)가 소유합니다. |
| Strategic election | Redis 후보 heartbeat 재등록과 결과 카운터·취소·interrupt 경계를 [Issue #804](https://github.com/bluetape4k/bluetape4k-leader/issues/804), [PR #802](https://github.com/bluetape4k/bluetape4k-leader/pull/802), [PR #830](https://github.com/bluetape4k/bluetape4k-leader/pull/830)으로 닫았습니다. |
| Prometheus example | [Issue #724](https://github.com/bluetape4k/bluetape4k-leader/issues/724)의 scheduler 시작 순서와 scrape readiness 분리를 [PR #840](https://github.com/bluetape4k/bluetape4k-leader/pull/840)으로 병합했습니다. HTTP status/body와 누락 metric 이름을 readiness 진단에 보존합니다. |
| Release-facing README | [Issue #753](https://github.com/bluetape4k/bluetape4k-leader/issues/753)와 [PR #838](https://github.com/bluetape4k/bluetape4k-leader/pull/838)에서 `0.5.0` stable·`1.0.0-SNAPSHOT` development·manual pin 경계를 정렬했습니다. |

## 현재 Release boundary

- 최신 안정 버전은 `0.5.0`이며 release commit은
  `721a9a3808f67489d2bdb8177734325981c24977`입니다.
- [GitHub Release 0.5.0](https://github.com/bluetape4k/bluetape4k-leader/releases/tag/0.5.0)은
  2026-08-06에 게시되었습니다.
- [Maven Central BOM 0.5.0](https://central.sonatype.com/artifact/io.github.bluetape4k.leader/bluetape4k-leader-bom/0.5.0)
  및 publishable module POM이 확인되었습니다.
- `docs/manual/manifest.yaml`은 `releaseRef: 0.5.0`과 위 release commit에
  고정되어 있습니다. 현재 `develop` 전용 diagnostics·observability 내용은
  `docs/manual/drafts/`에서 관리합니다.
- 1.0.0 release-prep #860은 `baseVersion=1.0.0`, 빈 `snapshotVersion`, 중앙
  catalog exact SHA `8efed120b91c4e1b1cfbfe1269321df325b08aef`를 사용합니다.
- `1.0.0` tag/release/publication은 아직 없으며, `1.0.0-post`의 [#774](https://github.com/bluetape4k/bluetape4k-leader/issues/774)는
  새 API를 포함하는 versioned release train에서 manual pin을 갱신한 뒤 닫습니다.

## 이전 기준일 (2026-08-15 KST)

- 당시 `develop` head는 `4b99338a1079ccf5d792b930238cf2f56f8f1929`였고,
  `0.5.0` release-readiness와 localization train이 완료된 상태였습니다.
- 당시 문서가 다음 개발선으로 기록한 `0.6.0`은 현재 milestone 정책으로
  대체되었습니다. 현재 개발선은 `1.0.0-SNAPSHOT`입니다.
