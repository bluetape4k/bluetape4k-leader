# 이슈 678 — README JVM 25 표기와 배포본 경계

## 맥락

루트 README의 배지와 요구사항은 JVM 25+를 가리키고 있었지만, 개요 다이어그램의 가상 스레드 카드에는 `JVM 21`이 남아 있었습니다. 매뉴얼은 `0.5.0` 배포 커밋을 고정하므로 현재 Snapshot 자산과 섞이지 않도록 별도의 경계 검증이 필요했습니다.

## 결정

현재 개발 자산인 `root-readme-overview-01.svg`와 2x PNG만 갱신하고, `docs/manual/manifest.yaml`의 `releaseRef`와 EN/KO 매뉴얼의 고정 URL은 변경하지 않습니다. `scripts/manual/readme_jvm25_contract.rb` 계약은 다음을 함께 검사합니다.

- EN/KO README의 JVM 25 배지·요구사항·개요 PNG embed
- SVG의 stale `JVM 21` 제거와 paired PNG의 2800x1800 크기
- 매뉴얼 `releaseRef`/`releaseCommit`과 EN/KO overview 링크의 고정 상태

## 검증

- `ruby scripts/manual/readme_jvm25_contract_test.rb`: 3 tests, 14 assertions, 0 failures
- `ruby scripts/manual/readme_jvm25_contract.rb`: 계약 통과
- `ruby -I scripts/manual -e 'Dir["scripts/manual/*_test.rb"].sort.each { |file| require File.expand_path(file) }'`: 36 tests, 384 assertions, 0 failures
- `ruby scripts/manual/sync_release_diagrams.rb --check`: failures=0, entries=103, release=0.5.0
- `ruby scripts/manual/export_manifest.rb --check`: snapshot current
- CairoSVG 2x render 및 SVG/PNG·XML·텍스트·arrowhead·connector·geometry·endpoint·mixed-corner·visual 감사 통과
- `git diff --check` 통과 및 최종 PNG full-size 육안 검사 통과

## 향후 지침

JVM 또는 Kotlin 기준을 올릴 때는 README 배지·요구사항과 README 개요 SVG를 함께 검색하고, 고정 릴리스 매뉴얼 URL은 `releaseRef`/`releaseCommit` 계약이 허용할 때만 갱신합니다. README 자산 디렉터리 전체 감사에서 대상과 무관한 미노출 PNG가 발견되더라도, 다른 자산을 임의로 노출하거나 삭제하지 말고 대상 쌍의 계약 결과와 범위 밖 경고를 분리해 기록합니다.
