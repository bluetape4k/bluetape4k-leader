# 문제 238 StateFlow 그룹 의미론

## 맥락

`leaderStateFlow()`는 단일 리더 투영입니다. 모든 `Revoked(lockName)`는 `LeaderState.empty(lockName)`에 매핑됩니다. `maxLeaders > 1`를 사용하는 그룹 선택기는 슬롯 하나가 취소된 후에도 여전히 활성 슬롯을 가질 수 있으므로 그룹 상태에 `leaderStateFlow()`를 사용하면 빈 잠금이 너무 일찍 보고될 수 있습니다.

## 결정

`leaderStateFlow()`를 단일 리더 API로 유지하고 해당 경계를 문서화하세요. 그룹 선택기용 `leaderGroupStateFlow(lockName, maxLeaders, scope, started)`를 추가하여 수명 주기 이벤트를 `LeaderGroupState.activeCount`에 투영합니다.

`LeaderElectionEvent.Revoked`에는 리더 또는 슬롯 ID가 없기 때문에 그룹 투영은 의도적으로 `leaders`를 비워 둡니다. 카운트 의미론은 균형 잡힌 선택/취소 이벤트에 대해 신뢰할 수 있습니다. 신원 의미론에는 향후 이벤트 계약 변경이 필요합니다.

## 결과

그룹 소비자는 전체 그룹을 비우기 위해 축소하지 않고도 부분적인 취소를 관찰할 수 있습니다. 기존 단일 리더 동작 및 Issue #237 열정적인 핫 플로우 구독 수정 사항은 변경되지 않았습니다.

## 검증

- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest' --no-configuration-cache --console=plain`
- 결과: 14개 테스트를 통과하고 빌드에 success했습니다.
- 부분 취소, 최대 리더 제한, 유효하지 않은 최대 리더, 건너뛴 이벤트 및 잠금 이름 필터링에 대한 테스트가 추가되었습니다.
- PR 이후 Claude 피드백은 그룹 `Skipped` 데드 코드 경로를 제거하고, `maxLeaders` 검증을 공개 함수로 이동하고, 건너뛴/필터링 테스트를 분할하고, 빈 `leaders` 불변성을 문서화했습니다.

## 미래 노트

그룹 이벤트 ID가 필요한 경우 ID 보존 그룹 상태를 추가하기 전에 리더 또는 슬롯 ID로 `LeaderElectionEvent.Revoked`를 확장하세요. 집계 전용 취소 이벤트에서 나머지 리더를 추론하지 마세요.
