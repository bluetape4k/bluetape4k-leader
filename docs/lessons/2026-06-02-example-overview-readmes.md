# 2026-06-02 — Example overview README refresh

## Context

`examples/`에는 15개 실행 예제가 있었지만 root overview README가 없었고, 일부 예제 README는 제공 언어 링크 형식과
Scenario / Architecture / Sequence coverage가 서로 달랐다.

## Decision

예제 README는 locale pair를 항상 함께 맞추고, 현재 언어에는 링크를 걸지 않는다. 예제 다이어그램은 기존 README
패턴처럼 최종 PNG를 링크하고, SVG / DOT / Graphviz evidence 파일은 검증과 재생성을 위한 source artifact로 함께 둔다.

## Outcome

`examples/README.md`와 `examples/README.ko.md`를 추가해 전체 예제를 backend/storage, scenario, good fit, command로
정리했다. Consul, etcd, Redisson watchdog, strategic election, virtual-thread runner에는 Architecture / Sequence
diagram을 추가했고, Prometheus dashboard에는 누락된 Sequence diagram을 추가했다.

## Verification

- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check`: `diagrams=61 failures=0`
- README locale / section / image-link validation: `examples=15 failures=0`
- `git diff --check`
- Visual contact sheet: `.omx/artifacts/examples-readme-new-diagrams-final-contact-sheet.png`

## Future Guidance

새 예제 README를 추가할 때는 root overview 표, English/Korean locale pair, Scenario / Architecture / Sequence 섹션,
README PNG link, and diagram source artifacts를 같은 변경에서 함께 갱신한다. README에는 `-graphviz.png` evidence 이미지가
아니라 최종 `.png`를 링크한다.
