# 2026-08-22 Issue #746 pending metadata 보존 경계

## 맥락

`ExportingLeaderHistorySink`는 acquisition 이후 terminal history를 만들기 위해
`LeaderAuditPendingContextStore`에 record의 context를 보관합니다. 기존 store는
entry 수와 TTL만 제한하고 `metadata.toMap()`을 그대로 저장했습니다. 따라서
public sink를 직접 구성하면 `SafeLeaderHistoryRecorder`의 metadata 제한을 거치지
않은 map이 최대 15분 동안 pending context에 남을 수 있었습니다.

## 결정

pending context 저장 경계에서 metadata를 먼저 정제합니다.

- 최대 16개 entry만 canonical key/value 순서로 선택합니다.
- key는 64자, value는 256자로 제한하고 제어 문자를 치환합니다.
- 선택된 map의 총 UTF-8 크기를 `LeaderAuditExportEvent.MAX_ATTRIBUTES_TOTAL_BYTES`
  (8192 bytes) 이하로 제한하며 마지막 value는 남은 byte 예산에 맞춰 자릅니다.
- 입력 map 전체를 정렬하지 않고 bounded priority queue만 유지해 oversized input을
  정제하는 동안 추가적인 전체 map 보존을 만들지 않습니다.
- token digest-only, TTL/eviction, 정상 terminal cleanup 및 #733의 실패·취소 cleanup
  계약은 변경하지 않습니다.

## 결과

직접 구성한 exporter sink도 pending context 단계에서 metadata 보존량이 제한되고,
terminal event가 원본 oversized map을 다시 보관하지 않습니다. 기존 recorder 경로의
redaction과 lifecycle 결과는 유지됩니다.

## 검증

- RED: focused 10개 테스트 중 3개가 metadata cardinality, 총 UTF-8 byte, direct sink
  terminal export 경계에서 의도대로 실패했습니다.
- GREEN: focused 11개 테스트 통과.
- `./gradlew :bluetape4k-leader-core:test --no-build-cache --console=plain`: 810개
  테스트 통과.
- `./gradlew detekt --no-configuration-cache --console=plain`: 통과.
- `./gradlew :bluetape4k-leader-core:detekt --configuration-cache --console=plain`:
  통과.
- `git diff --check`: 통과.
- root `./gradlew detekt --configuration-cache`는 기존
  `detektProductionSourceGuard`의 Gradle script/Project serialization 오류로
  실패했습니다. 변경 파일과 무관한 configuration-cache 인프라 문제이며
  `--no-configuration-cache` 경로와 leader-core Detekt는 통과했습니다.

## 퓨쳐 가드

비동기 export, audit, callback처럼 lifecycle 사이에 context를 보관하는 새 경로는
event 생성 시점의 상한만으로 충분하다고 가정하지 말고, 최초 저장 경계에서 key 수,
문자열 길이, 총 UTF-8 byte, 민감정보 redaction을 함께 적용해야 합니다. 큰 입력을
canonicalize할 때는 전체 map을 정렬해 임시 메모리를 늘리지 말고 bounded selection을
사용합니다.
