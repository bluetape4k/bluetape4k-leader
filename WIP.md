# WIP - bluetape4k-leader

스냅샷: 2026-07-03 KST 범위: 'debop'에 할당된 GitHub 문제를 공개합니다. 공개 횟수: 0.5.0 에픽을 종료한 후 백로그 문제가
12개 있습니다. 최종 에픽 종료를 제외하고 마일스톤 '0.5.0'이 완료되었습니다.

## 현재 방향

'0.5.0' 마일스톤이 제한되어 완료되었습니다. 관찰 가능성, 메트릭 카디널리티, Prometheus Runbook, Spring Boot 메타데이터, 시작
진단, 정확성 수정 및 벤치마크 증거에 대한 운영 준비 레인을 폐쇄했습니다.

릴리스 차단 결함이 발견되지 않는 한 추가 백로그 작업으로 '0.5.0'을 확장하지 마세요. 나머지 할당된 문제는 0.5.0 릴리스 종료 후 다음 마이너
라인으로 예약되어야 합니다.

## 활성 대기열

| 우선순위 | 이슈 | 마일스톤 | 메모 |
|---|---|---|---|
| P1 | [#532](https://github.com/bluetape4k/bluetape4k-leader/issues/532) 알려진 잠금에 대한 opt-in 리더 관리 작업 | Backlog | 보안에 민감한 관리 작업 표면입니다. 0.5.0 종료 범위와 분리합니다. |
| P1 | [#542](https://github.com/bluetape4k/bluetape4k-leader/issues/542) 라우트 범위 Ktor 리더 guard DSL | Backlog | 보안에 민감한 라우트 guard API입니다. 구현 전에 설계 검토가 필요합니다. |
| P1 | [#537](https://github.com/bluetape4k/bluetape4k-leader/issues/537) MVC/WebFlux 리더 게이트 라우트 도우미 | Backlog | Spring 라우트 도우미 표면입니다. 보안과 API 사용성 검토를 함께 수행합니다. |
| P2 | [#531](https://github.com/bluetape4k/bluetape4k-leader/issues/531) readiness 및 lease-risk health indicator | Backlog | 이후 운영 준비 증분에서 다룰 Spring health 표면입니다. |
| P2 | [#533](https://github.com/bluetape4k/bluetape4k-leader/issues/533) 백엔드 health 및 capability diagnostics SPI | Backlog | 교차 백엔드 진단 설계 후보입니다. |
| P2 | [#535](https://github.com/bluetape4k/bluetape4k-leader/issues/535) 플러그형 audit export adapter | Backlog | audit/export 통합 레인입니다. |
| P2 | [#536](https://github.com/bluetape4k/bluetape4k-leader/issues/536) 리더 인식 scheduled task adapter | Backlog | Spring scheduling 편의 API입니다. |
| P2 | [#539](https://github.com/bluetape4k/bluetape4k-leader/issues/539) SSE 및 WebSocket 리더 이벤트 스트림 | Backlog | Ktor 스트리밍 통합입니다. |
| P2 | [#540](https://github.com/bluetape4k/bluetape4k-leader/issues/540) Ktor StatusPages 및 구조화된 오류 통합 | Backlog | Ktor 오류 계약 통합입니다. |
| P2 | [#541](https://github.com/bluetape4k/bluetape4k-leader/issues/541) Ktor lifecycle 및 graceful shutdown hook | Backlog | Ktor plugin lifecycle 강화입니다. |
| P2 | [#559](https://github.com/bluetape4k/bluetape4k-leader/issues/559) lease-extension observation hook | Backlog | renewal 경로 observation 후속 작업입니다. |
| P3 | [#463](https://github.com/bluetape4k/bluetape4k-leader/issues/463) strategic group election API 설계 | Backlog | 설계 전용 API 탐색입니다. |

## Open PRs

이 종료 문서 분기 이전에는 공개 PR이 없었습니다.

## 최근 완료됨

- [#529](https://github.com/bluetape4k/bluetape4k-leader/issues/529)는 Micrometer Observation 및 OpenTelemetry bridge를 추가했습니다.
- [#530](https://github.com/bluetape4k/bluetape4k-leader/issues/530)는 metric tag cardinality control을 추가했습니다.
- [#534](https://github.com/bluetape4k/bluetape4k-leader/issues/534)는 Prometheus alert rule 및 leader runbook을 추가했습니다.
- [#538](https://github.com/bluetape4k/bluetape4k-leader/issues/538)는 Spring Boot configuration metadata 및 startup diagnostics를 추가했습니다.
- [#561](https://github.com/bluetape4k/bluetape4k-leader/issues/561)은 최종 0.5.0 에픽이며 이 문서 refresh가 병합된 후 종료되어야 합니다.

## 메모 새로 고침

- 2026-07-03 KST에 'gh'로 확인되었습니다.
- 마일스톤 `0.5.0`에는 미해결된 비에픽 문제가 없습니다.
- 'CHANGELOG.md'는 이제 0.5.0 관찰 가능성, 진단, 정확성 및 벤치마크 종료를 기록합니다.
- 릴리스 차단 결함이 발견되지 않는 한 0.5.0에서 남은 백로그 문제를 유지하세요.
